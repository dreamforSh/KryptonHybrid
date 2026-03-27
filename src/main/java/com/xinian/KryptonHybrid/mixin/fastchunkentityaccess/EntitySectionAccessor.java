package com.xinian.KryptonHybrid.mixin.fastchunkentityaccess;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@link EntitySection#storage} to allow reading entity collections directly.
 */
@Mixin(EntitySection.class)
public interface EntitySectionAccessor<T> {
    @Accessor("storage")
    ClassInstanceMultiMap<T> getStorage();
}

