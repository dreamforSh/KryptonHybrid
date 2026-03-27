package com.xinian.KryptonHybrid.mixin.fastchunkentityaccess;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the entity collection storage inside {@link EntitySection}.
 */
@Mixin(EntitySection.class)
public interface EntitySectionAccessor<T> {
    @Accessor("storage")
    ClassInstanceMultiMap<T> getStorage();
}

