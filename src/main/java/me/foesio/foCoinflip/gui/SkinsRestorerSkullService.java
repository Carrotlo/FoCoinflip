package me.foesio.foCoinflip.gui;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class SkinsRestorerSkullService {

    private static final String SKINS_RESTORER_PLUGIN = "SkinsRestorer";
    private static final String TEXTURES_PROPERTY = "textures";

    private final JavaPlugin plugin;
    private final Map<UUID, Optional<SkinTexture>> textureCache = new ConcurrentHashMap<>();

    private volatile boolean unavailable;
    private volatile PaperProfileBridge paperProfileBridge;
    private volatile SkinsRestorerBridge skinsRestorerBridge;

    SkinsRestorerSkullService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    boolean applySkin(SkullMeta meta, UUID ownerId, String ownerName) {
        if (unavailable || !Bukkit.getPluginManager().isPluginEnabled(SKINS_RESTORER_PLUGIN)) {
            return false;
        }

        Optional<SkinTexture> texture = textureCache.computeIfAbsent(ownerId, ignored -> fetchTexture(ownerId));
        if (texture.isEmpty()) {
            return false;
        }

        PaperProfileBridge bridge = paperProfileBridge();
        return bridge != null && bridge.apply(meta, ownerId, ownerName, texture.get());
    }

    private Optional<SkinTexture> fetchTexture(UUID ownerId) {
        SkinsRestorerBridge bridge = skinsRestorerBridge();
        if (bridge == null) {
            return Optional.empty();
        }
        return bridge.getStoredTexture(ownerId);
    }

    private SkinsRestorerBridge skinsRestorerBridge() {
        SkinsRestorerBridge bridge = skinsRestorerBridge;
        if (bridge != null) {
            return bridge;
        }

        try {
            bridge = SkinsRestorerBridge.create();
            skinsRestorerBridge = bridge;
            return bridge;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            unavailable = true;
            plugin.getLogger().fine("SkinsRestorer skull support unavailable: " + exception.getClass().getSimpleName());
            return null;
        }
    }

    private PaperProfileBridge paperProfileBridge() {
        PaperProfileBridge bridge = paperProfileBridge;
        if (bridge != null) {
            return bridge;
        }

        try {
            bridge = PaperProfileBridge.create();
            paperProfileBridge = bridge;
            return bridge;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            unavailable = true;
            plugin.getLogger().fine("Paper skull texture support unavailable: " + exception.getClass().getSimpleName());
            return null;
        }
    }

    private record SkinTexture(String value, String signature) {
    }

    private static final class SkinsRestorerBridge {

        private final Object playerStorage;
        private final Method getSkinOfPlayer;
        private final Method getValue;
        private final Method getSignature;

        private SkinsRestorerBridge(Object playerStorage, Method getSkinOfPlayer, Method getValue, Method getSignature) {
            this.playerStorage = playerStorage;
            this.getSkinOfPlayer = getSkinOfPlayer;
            this.getValue = getValue;
            this.getSignature = getSignature;
        }

        static SkinsRestorerBridge create() throws ReflectiveOperationException {
            Class<?> providerClass = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider");
            Object api = providerClass.getMethod("get").invoke(null);
            Object playerStorage = api.getClass().getMethod("getPlayerStorage").invoke(api);
            Method getSkinOfPlayer = playerStorage.getClass().getMethod("getSkinOfPlayer", UUID.class);
            Class<?> skinPropertyClass = Class.forName("net.skinsrestorer.api.property.SkinProperty");
            return new SkinsRestorerBridge(playerStorage, getSkinOfPlayer, skinPropertyClass.getMethod("getValue"),
                    skinPropertyClass.getMethod("getSignature"));
        }

        Optional<SkinTexture> getStoredTexture(UUID ownerId) {
            try {
                Object result = getSkinOfPlayer.invoke(playerStorage, ownerId);
                if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                    return Optional.empty();
                }

                Object skinProperty = optional.get();
                String value = (String) getValue.invoke(skinProperty);
                String signature = (String) getSignature.invoke(skinProperty);
                if (value == null || value.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new SkinTexture(value, signature));
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
                return Optional.empty();
            }
        }
    }

    private static final class PaperProfileBridge {

        private final Method createProfile;
        private final Class<?> profileClass;
        private final Constructor<?> signedPropertyConstructor;
        private final Constructor<?> unsignedPropertyConstructor;

        private PaperProfileBridge(Method createProfile, Class<?> profileClass, Constructor<?> signedPropertyConstructor,
                Constructor<?> unsignedPropertyConstructor) {
            this.createProfile = createProfile;
            this.profileClass = profileClass;
            this.signedPropertyConstructor = signedPropertyConstructor;
            this.unsignedPropertyConstructor = unsignedPropertyConstructor;
        }

        static PaperProfileBridge create() throws ReflectiveOperationException {
            Class<?> profileClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            Class<?> propertyClass = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
            return new PaperProfileBridge(
                    Bukkit.class.getMethod("createProfile", UUID.class, String.class),
                    profileClass,
                    propertyClass.getConstructor(String.class, String.class, String.class),
                    propertyClass.getConstructor(String.class, String.class));
        }

        boolean apply(SkullMeta meta, UUID ownerId, String ownerName, SkinTexture texture) {
            try {
                Object profile = createProfile.invoke(null, ownerId, ownerName);
                Object property = createProperty(texture);
                profileClass.getMethod("setProperty", property.getClass()).invoke(profile, property);
                meta.getClass().getMethod("setPlayerProfile", profileClass).invoke(meta, profile);
                return true;
            } catch (IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchMethodException
                    | RuntimeException exception) {
                return false;
            }
        }

        private Object createProperty(SkinTexture texture)
                throws InstantiationException, IllegalAccessException, InvocationTargetException {
            if (texture.signature() == null || texture.signature().isBlank()) {
                return unsignedPropertyConstructor.newInstance(TEXTURES_PROPERTY, texture.value());
            }
            return signedPropertyConstructor.newInstance(TEXTURES_PROPERTY, texture.value(), texture.signature());
        }
    }
}
