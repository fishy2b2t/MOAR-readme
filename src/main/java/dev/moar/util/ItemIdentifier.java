package dev.moar.util;

/*? if >=26.1 {*//*
import net.minecraft.core.component.DataComponents;
*//*?} else {*/
import net.minecraft.component.DataComponentTypes;
/*?}*/
/*? if >=26.1 {*//*
import net.minecraft.world.item.component.ItemContainerContents;
*//*?} else {*/
import net.minecraft.component.type.ContainerComponent;
/*?}*/
/*? if >=26.1 {*//*
import net.minecraft.world.item.enchantment.ItemEnchantments;
*//*?} else {*/
import net.minecraft.component.type.ItemEnchantmentsComponent;
/*?}*/
/*? if >=26.1 {*//*
import net.minecraft.world.item.enchantment.Enchantment;
*//*?} else {*/
import net.minecraft.enchantment.Enchantment;
/*?}*/
/*? if >=26.1 {*//*
import net.minecraft.world.item.ItemStack;
*//*?} else {*/
import net.minecraft.item.ItemStack;
/*?}*/
/*? if >=26.1 {*//*
import net.minecraft.world.item.Item;
*//*?} else {*/
import net.minecraft.item.Item;
/*?}*/
/*? if >=26.1 {*//*
import net.minecraft.core.registries.BuiltInRegistries;
*//*?} else {*/
import net.minecraft.registry.Registries;
/*?}*/
/*? if >=26.1 {*//*
import net.minecraft.core.Holder;
*//*?} else {*/
import net.minecraft.registry.entry.RegistryEntry;
/*?}*/

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Enriched item IDs — pickaxes get enchant suffixes, others use registry ID.
public final class ItemIdentifier {

    private ItemIdentifier() {}

    private static final Set<String> PICKAXE_IDS = Set.of(
            "minecraft:wooden_pickaxe",
            "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:golden_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe"
    );

    // Items are registry singletons, so base registry-ID strings never change.
    // Caching them eliminates Identifier.toString() allocation on every call.
    private static final Map<Item, String> BASE_ID_CACHE = new ConcurrentHashMap<>();

    private static String baseId(Item item) {
        return BASE_ID_CACHE.computeIfAbsent(item, i ->
                /*? if >=26.1 {*//*
                BuiltInRegistries.ITEM.getKey(i).toString()
                *//*?} else {*/
                Registries.ITEM.getId(i).toString()
                /*?}*/
        );
    }

    // Allocation-free equivalence check — same result as
    // getItemId(a).equals(getItemId(b)) without building strings.
    public static boolean matches(ItemStack a, ItemStack b) {
        if (a.getItem() != b.getItem()) return false;
        if (!PICKAXE_IDS.contains(baseId(a.getItem()))) return true;
        return Objects.equals(getPickaxeEnchantSuffix(a), getPickaxeEnchantSuffix(b));
    }

    // Item ID with pickaxe enchant suffix (e.g. [silk_touch]).
    public static String getItemId(ItemStack stack) {
        String baseId = baseId(stack.getItem());

        if (PICKAXE_IDS.contains(baseId)) {
            String suffix = getPickaxeEnchantSuffix(stack);
            if (suffix != null) {
                return baseId + "[" + suffix + "]";
            }
        }

        return baseId;
    }

    // Read shulker box contents with enriched item IDs.
    public static Map<String, Integer> readShulkerContents(ItemStack shulkerStack) {
        Map<String, Integer> contents = new HashMap<>();
        if (shulkerStack == null || shulkerStack.isEmpty()) return contents;

        /*? if >=26.1 {*//*
        ItemContainerContents cc = shulkerStack.get(DataComponents.CONTAINER);
        *//*?} else {*/
        ContainerComponent cc = shulkerStack.get(DataComponentTypes.CONTAINER);
        /*?}*/
        if (cc == null) return contents;

        /*? if >=26.1 {*//*
        for (ItemStack inner : cc.nonEmptyItemCopyStream().toList()) {
        *//*?} else {*/
        for (ItemStack inner : cc.iterateNonEmpty()) {
        /*?}*/
            String itemId = getItemId(inner);
            contents.merge(itemId, inner.getCount(), Integer::sum);
        }
        return contents;
    }

    private static String getPickaxeEnchantSuffix(ItemStack stack) {
        /*? if >=26.1 {*//*
        ItemEnchantments enchants = stack.getOrDefault(
        *//*?} else {*/
        ItemEnchantmentsComponent enchants = stack.getOrDefault(
        /*?}*/
                /*? if >=26.1 {*//*
                DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                *//*?} else {*/
                DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
                /*?}*/

        /*? if >=26.1 {*//*
        for (Holder<Enchantment> entry : enchants.keySet()) {
        *//*?} else {*/
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
        /*?}*/
            /*? if >=26.1 {*//*
            var key = entry.unwrapKey();
            *//*?} else {*/
            var key = entry.getKey();
            /*?}*/
            if (key.isPresent()) {
                /*? if >=26.1 {*//*
                String path = key.get().identifier().getPath();
                *//*?} else {*/
                String path = key.get().getValue().getPath();
                /*?}*/
                if ("silk_touch".equals(path)) return "silk_touch";
                if ("fortune".equals(path)) return "fortune";
            }
        }
        return null;
    }
}
