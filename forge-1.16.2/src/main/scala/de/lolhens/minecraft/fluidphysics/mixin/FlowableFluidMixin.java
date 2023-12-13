package de.lolhens.minecraft.fluidphysics.mixin;

import de.lolhens.minecraft.fluidphysics.FluidPhysicsMod;
import de.lolhens.minecraft.fluidphysics.util.FluidIsInfinite;
import de.lolhens.minecraft.fluidphysics.util.FluidSourceFinder;
import net.minecraft.block.BlockState;
import net.minecraft.block.DirectionalBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.TrapDoorBlock;
import net.minecraft.fluid.FlowingFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.properties.Half;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.IWorld;
import net.minecraft.world.IWorldReader;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scala.Option;

@Mixin(FlowingFluid.class)
public abstract class FlowableFluidMixin implements FlowableFluidAccessor {
    @Shadow
    protected abstract boolean isSameAs(FluidState state);

    private boolean canFlowDownIntoTrapdoor(BlockState state) {
        Fluid fluid = (FlowingFluid) (Object) this;
        if (fluid.isEquivalentTo(Fluids.WATER) && state.getBlock() instanceof TrapDoorBlock) {
            return !state.get(TrapDoorBlock.WATERLOGGED) &&
                    (state.get(TrapDoorBlock.HALF) == Half.BOTTOM || state.get(TrapDoorBlock.OPEN));
        }
        return false;
    }

    @Inject(at = @At("RETURN"), method = "func_211759_a", cancellable = true)
    private void func_211759_a(IBlockReader blockView,
                               Fluid fluid,
                               BlockPos pos,
                               BlockState state,
                               BlockPos fromPos,
                               BlockState fromState,
                               CallbackInfoReturnable<Boolean> info) {
        if (canFlowDownIntoTrapdoor(fromState)) {
            info.setReturnValue(true);
        } else if (info.getReturnValue() &&
                FluidPhysicsMod.config().isEnabledFor(fluid, (World) blockView, pos) &&
                FluidPhysicsMod.config().getFlowOverSources()) {
            FluidState fluidState = fromState.getFluidState();
            if (isSameAs(fluidState)) {
                info.setReturnValue(false);
            }
        }
    }

    @Inject(at = @At("HEAD"), method = "canFlow", cancellable = true)
    protected void canFlow(IBlockReader blockView,
                           BlockPos fluidPos,
                           BlockState fluidBlockState,
                           Direction flowDirection,
                           BlockPos flowTo,
                           BlockState flowToBlockState,
                           FluidState fluidState,
                           Fluid updatedFluid,
                           CallbackInfoReturnable<Boolean> info) {
        Fluid fluid = fluidState.getFluid();
        if (flowDirection == Direction.DOWN &&
                FluidPhysicsMod.config().isEnabledFor(fluid, (World) blockView, fluidPos)) {
            if (((FlowingFluid) (Object) this).isEquivalentTo(fluid)) {
                boolean isUnfillableAtSeaLevel = false;
                if (blockView instanceof World) {
                    World world = (World) blockView;
                    if (FluidPhysicsMod.config().isUnfillableInBiome(fluid, world, flowTo))
                        isUnfillableAtSeaLevel = flowTo.getY() == world.getSeaLevel() - 1;
                }
                if (!fluidState.isSource() || isUnfillableAtSeaLevel)
                    info.setReturnValue(true);
            } else if (canFlowDownIntoTrapdoor(flowToBlockState)) {
                info.setReturnValue(true);
            }
        }
    }

    @Inject(at = @At("HEAD"), method = "calculateCorrectFlowingState")
    protected void calculateCorrectFlowingState(IWorldReader world, BlockPos pos, BlockState state, CallbackInfoReturnable<FluidState> info) {
        FluidIsInfinite.set(world, pos);
    }

    @Shadow
    public abstract FluidState getStillFluidState(boolean falling);

    @Inject(at = @At("HEAD"), method = "flowInto", cancellable = true)
    protected void flowInto(IWorld world,
                            BlockPos pos,
                            BlockState state,
                            Direction direction,
                            FluidState fluidState,
                            CallbackInfo info) {
        FluidState still = getStillFluidState(false);

        if (!FluidPhysicsMod.config().isEnabledFor(still.getFluid(), (World) world, pos)) return;

        BlockPos up = pos.up();

        if (direction == Direction.DOWN || world.getFluidState(up).getFluid().isEquivalentTo(still.getFluid())) {
            BlockState blockStateBelow = world.getBlockState(pos.down());

            boolean isFlowingOntoPiston = blockStateBelow.getBlock() instanceof PistonBlock && blockStateBelow.get(DirectionalBlock.FACING) == Direction.UP;
            if (isFlowingOntoPiston) return;

            Option<BlockPos> sourcePos = FluidSourceFinder.findSource(world, up, still.getFluid());

            if (sourcePos.isDefined()) {
                FluidSourceFinder.moveSource(world, sourcePos.get(), pos, state, (FlowingFluid) (Object) this, still);

                // Cancel default flow algorithm
                info.cancel();
            } else if (isSameAs(state.getFluidState())) {
                // Cancel default flow algorithm if no source was found and new pos already contains the fluid source
                info.cancel();
            }
        }
    }
}
