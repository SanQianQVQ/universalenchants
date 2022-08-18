package fuzs.universalenchants.data;

import com.google.common.collect.BiMap;
import com.google.common.collect.EnumHashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fuzs.puzzleslib.json.JsonConfigFileUtil;
import fuzs.universalenchants.UniversalEnchants;
import fuzs.universalenchants.core.ModServices;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.enchantment.*;

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EnchantmentDataManager {
    /**
     * vanilla doesn't have an enchantment category just for axes, so we make our own
     */
    private static final EnchantmentCategory AXE_ENCHANTMENT_CATEGORY = ModServices.ABSTRACTIONS.createEnchantmentCategory("AXE", item -> item instanceof AxeItem);
    /**
     * we store these manually as vanilla's categories are the only ones we want to mess with, don't accidentally do something with our own or other mods' categories
     */
    public static final BiMap<EnchantmentCategory, ResourceLocation> ENCHANTMENT_CATEGORIES_BY_ID = EnchantmentCategoryMapBuilder.create()
            .putVanillaCategories(EnchantmentCategory.ARMOR, EnchantmentCategory.ARMOR_FEET, EnchantmentCategory.ARMOR_LEGS, EnchantmentCategory.ARMOR_CHEST, EnchantmentCategory.ARMOR_HEAD, EnchantmentCategory.WEAPON, EnchantmentCategory.DIGGER, EnchantmentCategory.FISHING_ROD, EnchantmentCategory.TRIDENT, EnchantmentCategory.BREAKABLE, EnchantmentCategory.BOW, EnchantmentCategory.WEARABLE, EnchantmentCategory.CROSSBOW, EnchantmentCategory.VANISHABLE)
            .putCategory(UniversalEnchants.MOD_ID, AXE_ENCHANTMENT_CATEGORY)
            .get();
    private static final List<AdditionalEnchantmentsData> ADDITIONAL_ENCHANTMENTS_DATA = ImmutableList.of(
            new AdditionalEnchantmentsData(EnchantmentCategory.WEAPON, Enchantments.IMPALING),
            new AdditionalEnchantmentsData(AXE_ENCHANTMENT_CATEGORY, Enchantments.SHARPNESS, Enchantments.SMITE, Enchantments.BANE_OF_ARTHROPODS, Enchantments.KNOCKBACK, Enchantments.FIRE_ASPECT, Enchantments.MOB_LOOTING, Enchantments.SWEEPING_EDGE, Enchantments.IMPALING),
            new AdditionalEnchantmentsData(EnchantmentCategory.TRIDENT, Enchantments.SHARPNESS, Enchantments.SMITE, Enchantments.BANE_OF_ARTHROPODS, Enchantments.KNOCKBACK, Enchantments.FIRE_ASPECT, Enchantments.MOB_LOOTING, Enchantments.SWEEPING_EDGE, Enchantments.QUICK_CHARGE, Enchantments.PIERCING),
            new AdditionalEnchantmentsData(EnchantmentCategory.BOW, Enchantments.PIERCING, Enchantments.MULTISHOT, Enchantments.QUICK_CHARGE, Enchantments.MOB_LOOTING),
            new AdditionalEnchantmentsData(EnchantmentCategory.CROSSBOW, Enchantments.FLAMING_ARROWS, Enchantments.PUNCH_ARROWS, Enchantments.POWER_ARROWS, Enchantments.INFINITY_ARROWS, Enchantments.MOB_LOOTING)
    );
    private static final Map<Enchantment, List<EnchantmentDataEntry<?>>> DEFAULT_CATEGORY_ENTRIES;
    private static final Map<Enchantment, EnchantmentDataHolder> CATEGORY_HOLDERS = getVanillaEnchantments().collect(Collectors.toMap(Function.identity(), EnchantmentDataHolder::new));
    
    static {
        Map<Enchantment, EnchantmentDataEntry.Builder> builders = getVanillaEnchantments().collect(Collectors.toMap(Function.identity(), EnchantmentDataEntry::defaultBuilder));
        ADDITIONAL_ENCHANTMENTS_DATA.forEach(data -> data.addToBuilder(builders));
        setupAdditionalCompatibility(builders);
        DEFAULT_CATEGORY_ENTRIES = builders.entrySet().stream().collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, e -> e.getValue().build()));
    }

    private static void setupAdditionalCompatibility(Map<Enchantment, EnchantmentDataEntry.Builder> builders) {
        applyIncompatibilityToBoth(builders, Enchantments.INFINITY_ARROWS, Enchantments.MENDING, false);
        applyIncompatibilityToBoth(builders, Enchantments.MULTISHOT, Enchantments.PIERCING, false);
        Registry.ENCHANTMENT.forEach(enchantment -> {
            if (enchantment instanceof DamageEnchantment && enchantment != Enchantments.SHARPNESS) {
                applyIncompatibilityToBoth(builders, Enchantments.SHARPNESS, enchantment, false);
                // we make impaling incompatible with damage enchantments as both can be applied to the same weapons now
                applyIncompatibilityToBoth(builders, Enchantments.IMPALING, enchantment, true);
            }
        });
        Registry.ENCHANTMENT.forEach(enchantment -> {
            if (enchantment instanceof ProtectionEnchantment && enchantment != Enchantments.ALL_DAMAGE_PROTECTION && enchantment != Enchantments.FALL_PROTECTION) {
                applyIncompatibilityToBoth(builders, Enchantments.ALL_DAMAGE_PROTECTION, enchantment, false);
            }
        });
    }

    private static void applyIncompatibilityToBoth(Map<Enchantment, EnchantmentDataEntry.Builder> builders, Enchantment enchantment, Enchantment other, boolean add) {
        BiConsumer<Enchantment, Enchantment> operation = (e1, e2) -> {
            EnchantmentDataEntry.Builder builder = builders.get(e1);
            if (add) {
                builder.add(e2);
            } else {
                builder.remove(e2);
            }
        };
        operation.accept(enchantment, other);
        operation.accept(other, enchantment);
    }

    public static void loadAll() {
        JsonConfigFileUtil.getAllAndLoad(UniversalEnchants.MOD_ID, EnchantmentDataManager::serializeDefaultDataEntries, EnchantmentDataManager::deserializeDataEntry, () -> CATEGORY_HOLDERS.values().forEach(EnchantmentDataHolder::invalidate));
        CATEGORY_HOLDERS.values().forEach(EnchantmentDataHolder::setEnchantmentCategory);
    }

    public static boolean isCompatibleWith(Enchantment enchantment, Enchantment other, boolean fallback) {
        // every enchantment is passed in here, but we only support vanilla, so make sure to handle modded properly
        return Optional.ofNullable(CATEGORY_HOLDERS.get(enchantment)).map(holder -> holder.isCompatibleWith(other, fallback)).orElse(fallback);
    }

    private static void serializeDefaultDataEntries(File directory) {
        serializeAllDataEntries(directory, DEFAULT_CATEGORY_ENTRIES);
    }

    private static void serializeAllDataEntries(File directory, Map<Enchantment, List<EnchantmentDataEntry<?>>> categoryEntries) {
        for (Map.Entry<Enchantment, List<EnchantmentDataEntry<?>>> entry : categoryEntries.entrySet()) {
            String fileName = "%s.json".formatted(Registry.ENCHANTMENT.getKey(entry.getKey()).getPath());
            File file = new File(directory, fileName);
            JsonConfigFileUtil.saveToFile(file, serializeDataEntry(entry.getKey(), entry.getValue()));
        }
    }

    private static JsonElement serializeDataEntry(Enchantment enchantment, Collection<EnchantmentDataEntry<?>> entries) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("id", Registry.ENCHANTMENT.getKey(enchantment).toString());
        JsonArray jsonArray = new JsonArray();
        JsonArray jsonArray1 = new JsonArray();
        for (EnchantmentDataEntry<?> entry : entries) {
            if (entry instanceof EnchantmentCategoryEntry) {
                entry.serialize(jsonArray);
            } else if (entry instanceof EnchantmentDataEntry.IncompatibleEntry) {
                entry.serialize(jsonArray1);
            }
        }
        jsonObject.add("items", jsonArray);
        jsonObject.add("incompatible", jsonArray1);
        return jsonObject;
    }

    private static void deserializeDataEntry(FileReader reader) {
        JsonElement jsonElement = JsonConfigFileUtil.GSON.fromJson(reader, JsonElement.class);
        JsonObject jsonObject = GsonHelper.convertToJsonObject(jsonElement, "enchantment config");
        ResourceLocation id = new ResourceLocation(GsonHelper.getAsString(jsonObject, "id"));
        EnchantmentDataHolder holder = CATEGORY_HOLDERS.get(Registry.ENCHANTMENT.get(id));
        JsonArray items = GsonHelper.getAsJsonArray(jsonObject, "items");
        for (JsonElement jsonElement1 : items) {
            String item;
            boolean exclude = false;
            if (jsonElement1.isJsonObject()) {
                JsonObject jsonObject1 = jsonElement1.getAsJsonObject();
                item = GsonHelper.getAsString(jsonObject1, "id");
                exclude = GsonHelper.getAsBoolean(jsonObject1, "exclude");
            } else {
                item = GsonHelper.convertToString(jsonElement1, "item");
            }
            EnchantmentCategoryEntry entry;
            if (item.startsWith("$")) {
                entry = EnchantmentCategoryEntry.CategoryEntry.deserialize(item);
            } else if (item.startsWith("#")) {
                entry = EnchantmentCategoryEntry.TagEntry.deserialize(item);
            } else {
                entry = EnchantmentCategoryEntry.ItemEntry.deserialize(item);
            }
            entry.setExclude(exclude);
            holder.submit(entry);
        }
        JsonArray jsonArray = GsonHelper.getAsJsonArray(jsonObject, "incompatible");
        String[] incompatibles = JsonConfigFileUtil.GSON.fromJson(jsonArray, String[].class);
        EnchantmentDataEntry<?> entry = EnchantmentDataEntry.IncompatibleEntry.deserialize(incompatibles);
        holder.submit(entry);
    }

    private static Stream<Enchantment> getVanillaEnchantments() {
        return Registry.ENCHANTMENT.entrySet().stream().filter(entry -> entry.getKey().location().getNamespace().equals("minecraft")).map(Map.Entry::getValue);
    }

    private static class EnchantmentCategoryMapBuilder {
        private final BiMap<EnchantmentCategory, ResourceLocation> map = EnumHashBiMap.create(EnchantmentCategory.class);

        public EnchantmentCategoryMapBuilder putVanillaCategories(EnchantmentCategory... categories) {
            for (EnchantmentCategory category : categories) {
                this.putCategory("minecraft", category);
            }
            return this;
        }

        public EnchantmentCategoryMapBuilder putCategory(String namespace, EnchantmentCategory category) {
            ResourceLocation location = new ResourceLocation(namespace, category.name().toLowerCase(Locale.ROOT));
            this.map.put(category, location);
            return this;
        }

        public BiMap<EnchantmentCategory, ResourceLocation> get() {
            return this.map;
        }

        public static EnchantmentCategoryMapBuilder create() {
            return new EnchantmentCategoryMapBuilder();
        }
    }

    private record AdditionalEnchantmentsData(EnchantmentCategory category, List<Enchantment> enchantments) {

        AdditionalEnchantmentsData(EnchantmentCategory category, Enchantment... enchantments) {
            this(category, ImmutableList.copyOf(enchantments));
        }

        public void addToBuilder(Map<Enchantment, EnchantmentDataEntry.Builder> builders) {
            for (Enchantment enchantment : this.enchantments) {
                builders.get(enchantment).add(this.category);
            }
        }
    }
}
