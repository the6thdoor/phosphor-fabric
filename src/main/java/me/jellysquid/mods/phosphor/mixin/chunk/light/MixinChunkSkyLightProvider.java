package me.jellysquid.mods.phosphor.mixin.chunk.light;

import org.objectweb.asm.Opcodes;
import me.jellysquid.mods.phosphor.common.chunk.level.LevelPropagatorExtended;
import me.jellysquid.mods.phosphor.common.chunk.light.LightProviderBlockAccess;
import me.jellysquid.mods.phosphor.common.util.LightUtil;
import me.jellysquid.mods.phosphor.common.util.math.ChunkSectionPosHelper;
import me.jellysquid.mods.phosphor.common.util.math.DirectionHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.ChunkSkyLightProvider;
import net.minecraft.world.chunk.light.SkyLightStorage;
import net.minecraft.world.chunk.light.LightStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Intrinsic;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.minecraft.util.math.ChunkSectionPos.getLocalCoord;
import static net.minecraft.util.math.ChunkSectionPos.getSectionCoord;

@Mixin(ChunkSkyLightProvider.class)
public abstract class MixinChunkSkyLightProvider extends ChunkLightProvider<SkyLightStorage.Data, SkyLightStorage>
        implements LevelPropagatorExtended, LightProviderBlockAccess {
    private static final BlockState AIR_BLOCK = Blocks.AIR.getDefaultState();

    @Shadow
    @Final
    private static Direction[] HORIZONTAL_DIRECTIONS;

    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    public MixinChunkSkyLightProvider(ChunkProvider chunkProvider, LightType type, SkyLightStorage lightStorage) {
        super(chunkProvider, type, lightStorage);
    }

    private int counterBranchA, counterBranchB, counterBranchC;

    /**
     * This breaks up the call to method_20479 into smaller parts so we do not have to pass a mutable heap object
     * to the method in order to extract the light result. This has a few other advantages, allowing us to:
     * - Avoid the de-optimization that occurs from allocating and passing a heap object
     * - Avoid unpacking coordinates twice for both the call to method_20479 and method_20710.
     * - Avoid the the specific usage of AtomicInteger, which has additional overhead for the atomic get/set operations.
     * - Avoid checking if the checked block is opaque twice.
     * - Avoid a redundant block state lookup by re-using {@param fromState}
     *
     * The rest of the implementation has been otherwise copied from vanilla, but is optimized to avoid constantly
     * (un)packing coordinates and to use an optimized direction lookup function.
     */
    @Inject(
        method = "getPropagatedLevel(JJI)I",
        at = @At(
                value = "RETURN",
                ordinal = 2,
                shift = At.Shift.AFTER
                ),
        cancellable = true
    )
    public void getPropLevel(long fromId, long toId, int currentLevel, CallbackInfoReturnable<Integer> ci) {
        // This patch is sort of ugly, but it works and is highly compatible with other mods.
        int toX = BlockPos.unpackLongX(toId);
        int toY = BlockPos.unpackLongY(toId);
        int toZ = BlockPos.unpackLongZ(toId);

        BlockState toState = this.getBlockStateForLighting(toX, toY, toZ);

        if (toState == null) {
            ci.setReturnValue(15);
        }

        int fromX = BlockPos.unpackLongX(fromId);
        int fromY = BlockPos.unpackLongY(fromId);
        int fromZ = BlockPos.unpackLongZ(fromId);

        BlockState fromState = null;

        if (fromState == null) {
            fromState = this.getBlockStateForLighting(fromX, fromY, fromZ);
        }

        // Most light updates will happen between two empty air blocks, so use this to assume some properties
        boolean airPropagation = toState == AIR_BLOCK && fromState == AIR_BLOCK;
        boolean verticalOnly = fromX == toX && fromZ == toZ;

        // The direction the light update is propagating
        Direction dir;
        Direction altDir = null;

        if (fromId == Long.MAX_VALUE) {
            dir = Direction.DOWN;
        } else {
            dir = DirectionHelper.getVecDirection(toX - fromX, toY - fromY, toZ - fromZ);

            if (dir == null) {
                altDir = DirectionHelper.getVecDirection(toX - fromX, verticalOnly ? -1 : 0, toZ - fromZ);

                if (altDir == null) {
                    ci.setReturnValue(15);
                }
            }
        }

        // Shape comparison checks are only meaningful if the blocks involved have non-empty shapes
        // If we're comparing between air blocks, this is meaningless
        if (!airPropagation) {
            // If the two blocks are directly adjacent...
            if (dir != null) {
                VoxelShape toShape = this.getOpaqueShape(toState, toX, toY, toZ, dir.getOpposite());

                if (toShape != VoxelShapes.fullCube()) {
                    VoxelShape fromShape = this.getOpaqueShape(fromState, fromX, fromY, fromZ, dir);

                    if (LightUtil.unionCoversFullCube(fromShape, toShape)) {
                        ci.setReturnValue(15);
                    }
                }
            } else {
                VoxelShape toShape = this.getOpaqueShape(toState, toX, toY, toZ, altDir.getOpposite());

                if (LightUtil.unionCoversFullCube(VoxelShapes.empty(), toShape)) {
                    ci.setReturnValue(15);
                }

                VoxelShape fromShape = this.getOpaqueShape(fromState, fromX, fromY, fromZ, Direction.DOWN);

                if (LightUtil.unionCoversFullCube(fromShape, VoxelShapes.empty())) {
                    ci.setReturnValue(15);
                }
            }
        }

        int out = this.getSubtractedLight(toState, toX, toY, toZ);

        if (out == 0 && currentLevel == 0 && (fromId == Long.MAX_VALUE || verticalOnly && fromY > toY)) {
            ci.setReturnValue(0);
        }

        ci.setReturnValue(currentLevel + Math.max(1, out));
    }

    @Unique
    BlockState fromState;

    @Unique
    int x, y, z;

    @Unique
    long chunkId; 

    @Unique
    long fromId;

    @Inject(
        method = "propagateLevel(JIZ)V",
        at = @At("HEAD")
    )
    private void storeId(long id, int targetLevel, boolean mergeAsMin, CallbackInfo ci) {
        this.fromId = id;
    }

    @Inject(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE_ASSIGN",
                target = "Lnet/minecraft/util/math/ChunkSectionPos;getSectionCoord(I)I"
                ),
        locals = LocalCapture.CAPTURE_FAILSOFT,
        cancellable = true
    )
    private void propagateFastPath(long id, int targetLevel, boolean mergeAsMin, CallbackInfo ci, long chunkId, int y, int localY, int chunkY) {
        this.x = BlockPos.unpackLongX(id);
        this.y = y;
        this.z = BlockPos.unpackLongZ(id);
        int localX = getLocalCoord(x);
        int localZ = getLocalCoord(z);
        this.chunkId = chunkId;

        BlockState fromState = this.getBlockStateForLighting(x, y, z);
        this.fromState = fromState;

        // Fast-path: Use much simpler logic if we do not need to access adjacent chunks
        if (localX > 0 && localX < 15 && localY > 0 && localY < 15 && localZ > 0 && localZ < 15) {
            for (Direction dir : DIRECTIONS) {
                this.propagateLevel(this.fromId, fromState, BlockPos.asLong(x + dir.getOffsetX(), y + dir.getOffsetY(), z + dir.getOffsetZ()), targetLevel, mergeAsMin);
            }

            ci.cancel();
        }
    }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/util/math/BlockPos;add(JIII)J",
                ordinal = 0
                )
    )
    private long computeBlockPosY(final long srcPos, final int dx, final int dy, final int dz) {
        return this.y + dy;
    }
    
    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/util/math/BlockPos;add(JIII)J",
                ordinal = 1
                )
    )
    private long computeBlockPos(final long srcPos, final int dx, final int dy, final int dz) {
        return BlockPos.asLong(x + dx, y + dy, z + dz);
    }
    
    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/util/math/BlockPos;offset(JLnet/minecraft/util/math/Direction;)J"
                )
    )
    private long offsetDirY(final long srcPos, final Direction dir) {
        return this.y + dir.getOffsetY();
    }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/util/math/ChunkSectionPos;fromGlobalPos(J)J",
                ordinal = 1
                )
    )
    private long sectionCoord1(final long y) {
        return getSectionCoord(Math.toIntExact(y));
    }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/util/math/ChunkSectionPos;fromGlobalPos(J)J",
                ordinal = 2
                )
    )
    private long sectionCoord2(final long y) {
        return getSectionCoord(Math.toIntExact(y));
    }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/chunk/light/SkyLightStorage;hasLight(J)Z",
                ordinal = 1
                )
    )
    private boolean optLookup1(final SkyLightStorage lightStorage, final long chunkY) {
        return lightStorage.hasLight(ChunkSectionPosHelper.updateYLong(this.chunkId, Math.toIntExact(chunkY)));
    }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/chunk/light/SkyLightStorage;hasLight(J)Z",
                ordinal = 2
                )
    )
    private boolean optLookup2(final SkyLightStorage lightStorage, final long chunkY) {
        return lightStorage.hasLight(ChunkSectionPosHelper.updateYLong(this.chunkId, Math.toIntExact(chunkY)));
    }

    // @Overwrite
    // public void propagateLevel(long sourceId, long targetId, int level, boolean mergeAsMin) {
    //     this.propagateLevel(sourceId, fromState, targetId, level, mergeAsMin);
    // }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/chunk/light/ChunkSkyLightProvider;propagateLevel(JJIZ)V",
                ordinal = 0
                )
    )
    private void propLevelY1(ChunkSkyLightProvider self, long id, long belowY, int level, boolean decrease) {
        // this.propagateLevel(this.tmpSrcPos, this.tmpSrcState, BlockPos.asLong(x, (int)belowY, z), level, decrease);
        this.propagateLevel(this.fromId, fromState, BlockPos.asLong(x, Math.toIntExact(belowY), z), level, decrease);
    }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/chunk/light/ChunkSkyLightProvider;propagateLevel(JJIZ)V",
                ordinal = 1
                )
    )
    private void propLevelY2(ChunkSkyLightProvider self, long id, long aboveY, int level, boolean decrease) {
        // this.propagateLevel(this.tmpSrcPos, fromState, BlockPos.asLong(x, (int)aboveY, z), level, decrease);
        this.propagateLevel(this.fromId, fromState, BlockPos.asLong(x, Math.toIntExact(aboveY), z), level, decrease);
    }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/chunk/light/ChunkSkyLightProvider;propagateLevel(JJIZ)V",
                ordinal = 2
                )
    )
    private void propLevel1(ChunkSkyLightProvider self, long srcId, long targetId, int level, boolean decrease) {
        this.propagateLevel(this.fromId, fromState, targetId, level, decrease);
    }

    @Redirect(
        method = "propagateLevel(JIZ)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/chunk/light/ChunkSkyLightProvider;propagateLevel(JJIZ)V",
                ordinal = 3
                )
    )
    private void propLevel2(ChunkSkyLightProvider self, long srcId, long targetId, int level, boolean decrease) {
        this.propagateLevel(this.fromId, fromState, targetId, level, decrease);
    }
}
