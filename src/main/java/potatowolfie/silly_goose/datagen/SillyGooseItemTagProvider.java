package potatowolfie.silly_goose.datagen;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import potatowolfie.silly_goose.item.SillyGooseItems;
import potatowolfie.silly_goose.registry.SillyGooseItemTags;

import java.util.concurrent.CompletableFuture;

public class SillyGooseItemTagProvider extends FabricTagsProvider.ItemTagsProvider {
    public SillyGooseItemTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> completableFuture) {
        super(output, completableFuture);
    }

    @Override
    protected void addTags(HolderLookup.Provider wrapperLookup) {
        builder(ItemTags.WOLF_FOOD)
                .add(BuiltInRegistries.ITEM.wrapAsHolder(SillyGooseItems.RAW_GOOSE).unwrapKey().orElseThrow())
                .add(BuiltInRegistries.ITEM.wrapAsHolder(SillyGooseItems.COOKED_GOOSE).unwrapKey().orElseThrow());

        builder(ItemTags.MEAT)
                .add(BuiltInRegistries.ITEM.wrapAsHolder(SillyGooseItems.RAW_GOOSE).unwrapKey().orElseThrow())
                .add(BuiltInRegistries.ITEM.wrapAsHolder(SillyGooseItems.COOKED_GOOSE).unwrapKey().orElseThrow());

        builder(SillyGooseItemTags.Item.GOOSE_EGGS)
                .add(BuiltInRegistries.ITEM.wrapAsHolder(SillyGooseItems.WHITE_EGG).unwrapKey().orElseThrow())
                .add(BuiltInRegistries.ITEM.wrapAsHolder(SillyGooseItems.BIG_WHITE_EGG).unwrapKey().orElseThrow())
                .add(BuiltInRegistries.ITEM.wrapAsHolder(SillyGooseItems.SMALL_WHITE_EGG).unwrapKey().orElseThrow());
    }
}
