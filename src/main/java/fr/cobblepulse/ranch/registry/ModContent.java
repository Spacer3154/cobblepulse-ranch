package fr.cobblepulse.ranch.registry;

import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.block.RanchBlock;
import fr.cobblepulse.ranch.block.RanchBlockEntity;
import fr.cobblepulse.ranch.item.HourglassItem;
import fr.cobblepulse.ranch.item.PokemonEggItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.List;

/** Blocs, objets, BlockEntity et composant de donnees (enregistres cote serveur et client : meme jar). */
public final class ModContent {
    public static final String[] INCENSES = {"sea", "lax", "rose", "pure", "rock", "odd", "luck", "full", "wave"};

    public static RanchBlock RANCH_BLOCK;
    public static BlockItem RANCH_ITEM;
    public static BlockEntityType<RanchBlockEntity> RANCH_BE;
    public static PokemonEggItem POKEMON_EGG;
    public static HourglassItem SILVER_HOURGLASS;
    public static HourglassItem GOLD_HOURGLASS;
    public static fr.cobblepulse.ranch.item.BottleCapItem SILVER_BOTTLE_CAP;
    public static fr.cobblepulse.ranch.item.BottleCapItem GOLD_BOTTLE_CAP;
    public static final List<Item> INCENSE_ITEMS = new ArrayList<>();
    public static DataComponentType<CompoundTag> EGG_DATA;

    private ModContent() {}

    public static void register() {
        EGG_DATA = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, CobbleRanch.id("egg"),
                DataComponentType.<CompoundTag>builder().persistent(CompoundTag.CODEC).networkSynchronized(ByteBufCodecs.COMPOUND_TAG).build());

        RANCH_BLOCK = Registry.register(BuiltInRegistries.BLOCK, CobbleRanch.id("ranch_block"), new RanchBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD).strength(2.5f, 3600000.0f).sound(SoundType.WOOD).noOcclusion().forceSolidOn()
                .pushReaction(PushReaction.BLOCK)));
        RANCH_ITEM = Registry.register(BuiltInRegistries.ITEM, CobbleRanch.id("ranch_block"), new BlockItem(RANCH_BLOCK,
                new Item.Properties().fireResistant()));
        RANCH_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, CobbleRanch.id("ranch_block"),
                BlockEntityType.Builder.of(RanchBlockEntity::new, RANCH_BLOCK).build(null));

        POKEMON_EGG = Registry.register(BuiltInRegistries.ITEM, CobbleRanch.id("pokemon_egg"),
                new PokemonEggItem(new Item.Properties().stacksTo(1).fireResistant()));
        SILVER_HOURGLASS = Registry.register(BuiltInRegistries.ITEM, CobbleRanch.id("silver_hourglass"),
                new HourglassItem(false, new Item.Properties().stacksTo(64).rarity(Rarity.UNCOMMON)));
        GOLD_HOURGLASS = Registry.register(BuiltInRegistries.ITEM, CobbleRanch.id("gold_hourglass"),
                new HourglassItem(true, new Item.Properties().stacksTo(64).rarity(Rarity.RARE)));
        SILVER_BOTTLE_CAP = Registry.register(BuiltInRegistries.ITEM, CobbleRanch.id("silver_bottle_cap"),
                new fr.cobblepulse.ranch.item.BottleCapItem(false, new Item.Properties().stacksTo(64)));
        GOLD_BOTTLE_CAP = Registry.register(BuiltInRegistries.ITEM, CobbleRanch.id("gold_bottle_cap"),
                new fr.cobblepulse.ranch.item.BottleCapItem(true, new Item.Properties().stacksTo(64)));
        for (String s : INCENSES) {
            INCENSE_ITEMS.add(Registry.register(BuiltInRegistries.ITEM, CobbleRanch.id(s + "_incense"), new Item(new Item.Properties().stacksTo(64))));
        }

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ResourceKey.create(Registries.CREATIVE_MODE_TAB, CobbleRanch.id("main")),
                FabricItemGroup.builder().title(Component.translatable("itemGroup.cobblepulse_ranch.main")).icon(() -> new ItemStack(RANCH_ITEM))
                        .displayItems((params, out) -> {
                            out.accept(RANCH_ITEM);
                            out.accept(SILVER_HOURGLASS);
                            out.accept(GOLD_HOURGLASS);
                            out.accept(SILVER_BOTTLE_CAP);
                            out.accept(GOLD_BOTTLE_CAP);
                            for (Item i : INCENSE_ITEMS) out.accept(i);
                        }).build());
    }
}
