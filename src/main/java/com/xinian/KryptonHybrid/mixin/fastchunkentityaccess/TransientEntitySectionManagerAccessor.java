package com.xinian.KryptonHybrid.mixin.fastchunkentityaccess;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the backing section storage in {@link TransientEntitySectionManager}.
 */
@Mixin(TransientEntitySectionManager.class)
public interface TransientEntitySectionManagerAccessor<T extends EntityAccess> {
    @Accessor("sectionStorage")
    EntitySectionStorage<T> getSectionStorage();
}

