package com.plumestweaks.bridge;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 运行时反射桥接，避免编译期对 Paraglider 模组的依赖。
 * 通过 Stamina API 将 FastMove 动作消耗重定向到耐力条。
 */
public final class ParagliderBridge {
    private static Boolean available;
    private static Method staminaGet;
    private static Method takeStamina;
    private static Method staminaValue;
    private static Method maxStaminaValue;
    private static Method isDepleted;
    private static Method staminaSet;
    private static Method giveStamina;

    private ParagliderBridge() {}

    public static boolean isAvailable() {
        if (available == null) init();
        return available;
    }

    @Nullable
    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name, false, ParagliderBridge.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {}
        try {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null) return Class.forName(name, false, ctx);
        } catch (ClassNotFoundException ignored) {}
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            if (sys != null) return Class.forName(name, false, sys);
        } catch (ClassNotFoundException ignored) {}
        return null;
    }

    private static int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        throw new ClassCastException("Cannot convert " + val.getClass().getName() + " to int");
    }

    private static void init() {
        try {
            Class<?> staminaClass = findClass("tictim.paraglider.api.stamina.Stamina");
            if (staminaClass == null) {
                available = false;
                return;
            }
            for (Method m : staminaClass.getMethods()) {
                String n = m.getName();
                int pc = m.getParameterCount();
                if (n.equals("get") && pc == 1 && m.getParameterTypes()[0] == Player.class) staminaGet = m;
                else if (n.equals("takeStamina") && pc == 3) takeStamina = m;
                else if (n.equals("stamina") && pc == 0) staminaValue = m;
                else if (n.equals("maxStamina") && pc == 0) maxStaminaValue = m;
                else if (n.equals("isDepleted") && pc == 0) isDepleted = m;
                else if (n.equals("setStamina") && pc == 1) staminaSet = m;
                else if (n.equals("giveStamina") && pc == 2) giveStamina = m;
            }
            available = staminaGet != null && takeStamina != null && staminaValue != null
                    && maxStaminaValue != null && isDepleted != null && staminaSet != null && giveStamina != null;
        } catch (Throwable e) {
            available = false;
        }
    }

    @Nullable
    private static Object getStamina(Player player) {
        if (!isAvailable()) return null;
        try {
            return staminaGet.invoke(null, player);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 扣除耐力，返回实际扣除量。耐力耗尽或不可用时返回 0。 */
    public static int takeStamina(Player player, int amount) {
        Object stamina = getStamina(player);
        if (stamina == null) return 0;
        try {
            int current = toInt(staminaValue.invoke(stamina));
            int max = toInt(maxStaminaValue.invoke(stamina));
            boolean depleted = (boolean) isDepleted.invoke(stamina);

            // 新玩家 stamina=0, !depleted, max>0 — 补满再扣
            if (current <= 0 && !depleted && max > 0) {
                staminaSet.invoke(stamina, max);
                current = max;
            }
            if (current <= 0) return 0;

            return toInt(takeStamina.invoke(stamina, amount, false, false));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 恢复耐力，返回实际恢复量。耐力满或不可用时返回 0。 */
    public static int giveStamina(Player player, int amount) {
        Object stamina = getStamina(player);
        if (stamina == null) return 0;
        try {
            return toInt(giveStamina.invoke(stamina, amount, false));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 当前耐力值，不可用时返回 -1。 */
    public static int getStaminaValue(Player player) {
        Object stamina = getStamina(player);
        if (stamina == null) return -1;
        try {
            return toInt(staminaValue.invoke(stamina));
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 最大耐力值，不可用时返回 -1。 */
    public static int getMaxStamina(Player player) {
        Object stamina = getStamina(player);
        if (stamina == null) return -1;
        try {
            return toInt(maxStaminaValue.invoke(stamina));
        } catch (Throwable e) {
            return -1;
        }
    }
}
