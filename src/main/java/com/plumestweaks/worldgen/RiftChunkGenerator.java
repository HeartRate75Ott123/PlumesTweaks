package com.plumestweaks.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 时空裂隙 —— 桃源空岛区块生成器。
 * <p>
 * 地形：中央平坦平原（Y=128，3 格厚）→ 山脉环 → 过渡 → 虚空。
 * 平原底部悬挂穹顶 + 根脉群（三频噪声阈值生成长短根须），
 * 中心总深 ~50 格，边缘 ~20 格，呈现浮空岛屿根脉下垂效果。
 * 厚度计算采用 4 格网格采样 + 双线性插值，消除块级取整突兀。
 */
public class RiftChunkGenerator extends ChunkGenerator {

    private static final int PLAIN_Y = 128;
    private static final int PEAK_Y = 192;
    private static final double PLAIN_R = 64.0;       // 平原半径
    private static final double MOUNTAIN_END_R = 112.0; // 山脉外缘
    private static final double MAX_R = 116.0;          // 绝对最大半径
    private static final int MAX_THICKNESS = PEAK_Y - PLAIN_Y + 1; // 65

    /** 采样步长（格），4 格网格 → 16x16 区块 = 5x5 采样点 */
    private static final int STEP = 4;

    public RiftChunkGenerator(BiomeSource biomeSource,
                              Function<Holder<Biome>, BiomeGenerationSettings> genSettingsFactory) {
        super(biomeSource, genSettingsFactory);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState,
                                                         StructureManager structureManager, ChunkAccess chunk) {
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        if (Math.sqrt((cx * 16 + 8) * (cx * 16 + 8) + (cz * 16 + 8) * (cz * 16 + 8)) > MAX_R + 8)
            return CompletableFuture.completedFuture(chunk);

        int ox = cx << 4, oz = cz << 4; // chunk origin in world coords

        // ---- 4 格网格采样（向上 + 向下厚度） ----
        int samplesX = 16 / STEP + 1; // 5
        double[][] upGrid = new double[samplesX][samplesX];
        double[][] downGrid = new double[samplesX][samplesX];
        for (int gx = 0; gx < samplesX; gx++) {
            for (int gz = 0; gz < samplesX; gz++) {
                int sx = ox + gx * STEP, sz = oz + gz * STEP;
                upGrid[gz][gx] = rawThickness(sx, sz);
                downGrid[gz][gx] = rawHangingThickness(sx, sz);
            }
        }

        // ---- 双线性插值放置方块 ----
        for (int lx = 0; lx < 16; lx++) {
            float tx = lx / (float) STEP;
            int gx0 = (int) tx;
            int gx1 = Math.min(gx0 + 1, samplesX - 1);
            float fracX = tx - gx0;

            for (int lz = 0; lz < 16; lz++) {
                float tz = lz / (float) STEP;
                int gz0 = (int) tz;
                int gz1 = Math.min(gz0 + 1, samplesX - 1);
                float fracZ = tz - gz0;

                // 双线性插值（向上厚度）
                double upH = lerp(lerp(upGrid[gz0][gx0], upGrid[gz0][gx1], fracX),
                                  lerp(upGrid[gz1][gx0], upGrid[gz1][gx1], fracX), fracZ);
                // 双线性插值（向下悬挂厚度）
                double dnH = lerp(lerp(downGrid[gz0][gx0], downGrid[gz0][gx1], fracX),
                                  lerp(downGrid[gz1][gx0], downGrid[gz1][gx1], fracX), fracZ);

                int upThick = (int) (upH + 0.5);
                int downThick = (int) (dnH + 0.5);
                if (upThick <= 0 && downThick <= 0) continue;

                int wx = ox + lx, wz = oz + lz;

                // 放置向上方块（原逻辑，PLAIN_Y 向上）
                for (int y = 0; y < upThick; y++) {
                    chunk.setBlockState(new BlockPos(wx, PLAIN_Y + y, wz),
                            blockFor(y, upThick, wx, wz), false);
                }
                // 放置悬挂方块（PLAIN_Y 向下）
                for (int y = 1; y <= downThick; y++) {
                    chunk.setBlockState(new BlockPos(wx, PLAIN_Y - y, wz),
                            blockForHanging(y - 1, downThick, wx, wz), false);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    // ---- 线性插值 ----
    private static double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 计算浮点厚度（连续值）。
     * 仿 Terralith 多层叠加：
     *   1. 宽基座 swell（sin² 廓线，提供山体厚度）
     *   2. 脊线网络（abs(噪声) 折叠产生 V 形脊线，多频叠加）
     *   3. 高度调制（低频噪声，产生错落感）
     * 三者通过 max() + 加法组合。
     */
    private static double rawThickness(int wx, int wz) {
        double raw = Math.sqrt(wx * (double) wx + wz * (double) wz);
        if (raw > MAX_R) return 0;

        double r = raw + sinNoise2D(wx, wz, 0.02, 0.025, 3.0) * 2.0;
        if (r <= PLAIN_R) return 1.0;

        // 归一化山脉带位置
        double t = (r - PLAIN_R) / (MOUNTAIN_END_R - PLAIN_R);
        if (t > 1.0) {
            t = (MAX_R - r) / (MAX_R - MOUNTAIN_END_R);
            if (t <= 0) return 0;
            return Math.min(t * t * 12 + 0.5, MAX_THICKNESS);
        }

        // Layer 1: 宽基座 swell（sin²，0→1→0）
        double swell = sinNoise2D(wx, wz, 0.01, 0.012, 4.0);
        double baseCurve = Math.sin(t * Math.PI);
        double broadBase = baseCurve * baseCurve * (18.0 + swell * 4.0);

        // Layer 2: 脊线 — abs(噪声) 折叠，多频叠加
        double r1 = Math.abs(sinNoise2D(wx, wz, 0.035, 0.04, 4.0)) * 32.0;
        double r2 = Math.abs(sinNoise2D(wx, wz, 0.07, 0.075, 4.0)) * 14.0;
        double r3 = Math.abs(sinNoise2D(wx, wz, 0.015, 0.018, 4.0)) * 18.0;
        double ridges = (r1 + r2 + r3) * baseCurve * 0.5;

        // Layer 3: 高度调制 — 让峰顶有错落
        double hMod = sinNoise2D(wx, wz, 0.012, 0.014, 4.0) * 10.0 * baseCurve;

        // 组合：max(基座, 脊线) + 高度调制，仿 Terralith 的 max() 策略
        double thick = 1.0 + Math.max(broadBase, ridges) + Math.max(0, hMod);
        return Math.min(thick, MAX_THICKNESS);
    }

    // ========== 根脉下垂（PLAIN_Y = 128 以下，追平山高 64 格） ==========

    /** 平原底部 2 格泥土（Y=127, Y=126） */
    private static final double PLAIN_BASE_DEPTH = 2.0;
    /** 基座体量（中心总贡献 36+2=38，追平山高须 +26 格强根脉 → 64） */
    private static final double BODY_CENTER_DEPTH = 36.0;
    /** 根脉最大长度（主根比山更高，追至 ~80 格） */
    private static final double MAIN_ROOT_MAX = 50.0;   // 主根 0-50
    private static final double MED_ROOT_MAX = 22.0;    // 次根 0-22
    private static final double FINE_ROOT_MAX = 10.0;   // 细根 0-10

    /**
     * 计算根脉下垂厚度。
     * <p>
     * 基座体量 + 根脉 + 噪声，三者统一乘以径向衰减（t²）。
     * 根脉由三频噪声阈值生成，覆盖整个岛屿（含山脉底下），
     * 中心强根可达 ~64 格（追平山高），向外缘平滑收敛至虚空。
     */
    private static double rawHangingThickness(int wx, int wz) {
        double raw = Math.sqrt(wx * (double) wx + wz * (double) wz);
        if (raw > MAX_R) return 0;

        double r = raw + sinNoise2D(wx, wz, 0.02, 0.025, 3.0) * 2.0;

        // 全局径向衰减（t² 凸曲线，中心厚边缘薄）
        double t = Math.min(r / MAX_R, 1.0);
        double fade = Math.max(0, 1.0 - t * t);

        // 基座体量
        double body = (BODY_CENTER_DEPTH + PLAIN_BASE_DEPTH) * fade;

        // 根脉（全岛覆盖，随 fade 衰减）
        double n1 = Math.max(0, sinNoise2D(wx, wz, 0.04, 0.045, 1.0) - 0.35) / 0.65;
        double n2 = Math.max(0, sinNoise2D(wx, wz, 0.09, 0.095, 1.0) - 0.25) / 0.75;
        double n3 = Math.max(0, sinNoise2D(wx, wz, 0.18, 0.19, 1.0) - 0.15) / 0.85;
        double roots = (n1 * n1 * MAIN_ROOT_MAX + n2 * n2 * MED_ROOT_MAX + n3 * n3 * FINE_ROOT_MAX) * fade;

        // 表面起伏
        double noise = sinNoise2D(wx, wz, 0.035, 0.04, 1.0) * 2.0 * fade;

        return Math.max(0, body + roots + noise);
    }

    /** 多频正弦合成连续 2D 起伏 */
    private static double sinNoise2D(int x, int z, double f1, double f2, double amp) {
        double v = 0;
        v += Math.sin(x * f1 + z * f2) * 1.0;
        v += Math.sin(x * f1 * 2.3 + z * f2 * 1.7) * 0.5;
        v += Math.sin(x * f1 * 5.1 + z * f2 * 4.3) * 0.25;
        v += Math.cos(x * f1 * 11.7 + z * f2 * 9.1) * 0.125;
        return v / 1.875 * amp;
    }

    /** 多频正弦合成连续 3D 起伏，用于岩层混合 */
    private static double sinNoise3D(int x, int y, int z, double f, double amp) {
        double v = 0;
        v += Math.sin(x * f + y * f * 1.7 + z * f * 0.3) * 1.0;
        v += Math.sin(x * f * 2.3 + y * f * 0.9 + z * f * 1.7) * 0.5;
        v += Math.sin(x * f * 5.1 + y * f * 3.7 + z * f * 4.3) * 0.25;
        v += Math.cos(x * f * 11.7 + y * f * 6.1 + z * f * 9.1) * 0.125;
        return v / 1.875 * amp;
    }

    // ========== 标准覆写 ==========

    @Override public void applyCarvers(WorldGenRegion r, long l, RandomState rs,
                                        BiomeManager bm, StructureManager sm,
                                        ChunkAccess ca, GenerationStep.Carving c) {}
    @Override public void buildSurface(WorldGenRegion r, StructureManager sm,
                                        RandomState rs, ChunkAccess ca) {}
    @Override public void spawnOriginalMobs(WorldGenRegion r) {}
    @Override public int getGenDepth() { return 512; }
    @Override public int getSeaLevel() { return PLAIN_Y; }
    @Override public int getMinY() { return 0; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             net.minecraft.world.level.LevelHeightAccessor ha, RandomState rs) {
        int t = (int) (rawThickness(x, z) + 0.5);
        if (t <= 0) return getMinY();
        if (type == Heightmap.Types.OCEAN_FLOOR_WG || type == Heightmap.Types.OCEAN_FLOOR)
            return PLAIN_Y;
        return PLAIN_Y + t - 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z,
                                     net.minecraft.world.level.LevelHeightAccessor ha, RandomState rs) {
        int up = (int) (rawThickness(x, z) + 0.5);
        int dn = (int) (rawHangingThickness(x, z) + 0.5);
        if (up <= 0 && dn <= 0) return new NoiseColumn(getMinY(), new BlockState[0]);

        int total = up + dn;
        BlockState[] col = new BlockState[total];
        // 向上部分
        for (int i = 0; i < up; i++) col[i] = blockFor(i, up, x, z);
        // 向下部分（追加在向上部分之后，getBaseColumn 从 minY 开始构建）
        for (int i = 0; i < dn; i++) col[up + i] = blockForHanging(i, dn, x, z);

        return new NoiseColumn(PLAIN_Y - dn, col);
    }

    @Override
    public void addDebugScreenInfo(List<String> list, RandomState rs, BlockPos pos) {
        int dn = (int) (rawHangingThickness(pos.getX(), pos.getZ()) + 0.5);
        list.add("Rift: plain=%d peak=%d r=%.0f-%.0f hang=%d"
                .formatted(PLAIN_Y, PEAK_Y, PLAIN_R, MOUNTAIN_END_R, dn));
    }

    // ========== 方块分层 ==========

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState ANDESITE = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState TUFF = Blocks.TUFF.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    /**
     * 为给定位置选择方块。
     * <p>
     * 草皮从平原向外延伸覆盖低矮山丘（grass erosion），
     * 山体过渡带（厚 6-9）正弦混合草→石，
     * 山体表面 3D 噪声混合安山岩/凝灰岩/石头（约 1:1），
     * 陡坡棱角额外点缀凝灰岩。
     * <ul>
     *   <li>厚 ≤ 5：全部草方块覆盖（侵蚀区）</li>
     *   <li>厚 6-9：正弦过渡，噪声控制草→石斑块比例</li>
     *   <li>厚 ≥ 10：山体表面 3D 噪声混合 1:1</li>
     * </ul>
     */
    private static BlockState blockFor(int y, int thick, int wx, int wz) {
        int wy = PLAIN_Y + y;

        // === 草皮侵蚀：厚 ≤ 5 全部草方块覆盖 ===
        if (thick <= 5) {
            if (y == thick - 1) return GRASS;
            if (y >= thick - 3) return DIRT;
            return STONE;
        }

        // === 山体过渡带：正弦平滑混合草→石 (厚 6-9) ===
        if (thick <= 9) {
            // 用表面 y（始终取 surface y）算一次过渡判断，保证上下层植冠一致
            boolean isGrass = useGrassAtSurface(wx, PLAIN_Y + thick - 1, wz, thick);
            if (y == thick - 1) return isGrass ? GRASS : getMixedStone(wx, wy, wz);
            if (y == thick - 2) return isGrass ? DIRT : getMixedStone(wx, wy, wz);
            return STONE;
        }

        // === 山体 (thick ≥ 10) ===
        if (thick - y <= 2) return getMixedStone(wx, wy, wz);
        return STONE;
    }

    /**
     * 为根脉下垂区域选择方块（PLAIN_Y = 128 以下）。
     * <p>
     * - 平原底部（Y=127, 126）：泥土
     * - 穹顶上部：粗泥 → 石头渐变
     * - 穹顶主体：石头 + 安山岩
     * - 根脉端部（底部 2 格）：圆石 + 混合石材，风化质感
     */
    private static BlockState blockForHanging(int yBelow, int downThick, int wx, int wz) {
        int wy = PLAIN_Y - 1 - yBelow;

        // 平原底部（Y=127, 126）：泥土
        if (yBelow <= 1) return Blocks.DIRT.defaultBlockState();

        // 上部过渡（穹顶表层）：粗泥 → 石头
        if (yBelow <= 3) {
            double n = sinNoise3D(wx, wy, wz, 0.05, 1.0);
            if (n > 0.3) return Blocks.COARSE_DIRT.defaultBlockState();
            if (n > -0.3) return Blocks.DIRT.defaultBlockState();
            return STONE;
        }

        // 根脉端部（底部 2 格）：混合石材
        if (yBelow >= downThick - 2) {
            return getMixedStone(wx, wy, wz);
        }

        // 主体：石头 + 安山岩
        double n = sinNoise3D(wx, wy, wz, 0.035, 1.0);
        if (n < -0.4) return ANDESITE;
        return STONE;
    }

    /** 高低分界：≤ 此 Y 只出凝灰岩，> 此 Y 只出安山岩 */
    private static final int ZONE_SPLIT_Y = PLAIN_Y + 23; // wy = 55

    /**
     * 3D 噪声混合，高低分区 + 同高度覆盖均匀。
     * <ul>
     *   <li>低处（wy ≤ 55）：石头 + 凝灰岩笔刷（陡坡）</li>
     *   <li>高处（wy > 55）：石头 + 安山岩边缘笔刷（随高度递增）</li>
     * </ul>
     * 噪声混合策略：60% Y 轴主导 + 40% 水平扰动，
     * 确保同一高度一圈看过去点缀量相近，笔刷轮廓仍流畅。
     */
    private static BlockState getMixedStone(int wx, int wy, int wz) {
        double n = yBlendNoise(wx, wy, wz, 0.03);

        if (wy <= ZONE_SPLIT_Y) {
            // === 低处：石头 + 凝灰岩笔刷（陡坡棱角） ===
            if (isSteep(wx, wz)) {
                double t = yBlendNoise(wx + 100, wy - 50, wz + 200, 0.04);
                if (t < -0.35) return TUFF;
            }
            return STONE;
        }

        // === 高处：石头 + 安山岩边缘笔刷，越往顶越多 ===
        double h = (wy - ZONE_SPLIT_Y) / (double) (PEAK_Y - ZONE_SPLIT_Y);
        if (n < -0.1 + h * 0.35) return ANDESITE;
        return STONE;
    }

    /** 60% Y 轴 + 40% 全 3D 的混合噪声，同高度覆盖均匀，笔刷边缘流畅 */
    private static double yBlendNoise(int wx, int wy, int wz, double f) {
        double full = sinNoise3D(wx, wy, wz, f, 1.0);
        double yOnly = sinNoise3D(0, wy, 0, f, 1.0);
        return yOnly * 0.6 + full * 0.4;
    }

    /**
     * 正弦过渡判断：表面方块在草→石之间的混合权重。
     * 厚 6→9，cos(t·π/2) 从 1→0 平滑递减，配合 3D 噪声决定该位置是否为草方块。
     * <ul>
     *   <li>厚 6：~92% 草 ≫ 8% 石</li>
     *   <li>厚 7：~71% 草 ≫ 29% 石</li>
     *   <li>厚 8：~38% 草 ≪ 62% 石</li>
     *   <li>厚 9：0% 草 → 全石</li>
     * </ul>
     */
    private static boolean useGrassAtSurface(int wx, int wy, int wz, int thick) {
        double t = (thick - 5) / 4.0; // 0→1
        double grassWeight = Math.cos(t * Math.PI / 2);
        double noise = (sinNoise3D(wx, wy, wz, 0.03, 1.0) + 1) / 2; // [0,1]
        return noise < grassWeight;
    }

    /** 用厚度函数的梯度检测陡峭度，模仿 Terralith 的 steep 条件 */
    private static boolean isSteep(int wx, int wz) {
        double dx = rawThickness(wx + 2, wz) - rawThickness(wx - 2, wz);
        double dz = rawThickness(wx, wz + 2) - rawThickness(wx, wz - 2);
        return dx * dx + dz * dz > 9.0;
    }


    // ========== 编解码器 ==========

    public static final Codec<RiftChunkGenerator> CODEC = RecordCodecBuilder.create(
            inst -> inst.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource)
            ).apply(inst, (BiomeSource bs) ->
                    new RiftChunkGenerator(bs, h -> BiomeGenerationSettings.EMPTY))
    );

    @Override
    public Codec<? extends ChunkGenerator> codec() { return CODEC; }
}
