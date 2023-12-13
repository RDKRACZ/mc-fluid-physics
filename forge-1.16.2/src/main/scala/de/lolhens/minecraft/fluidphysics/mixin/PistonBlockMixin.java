package de.lolhens.minecraft.fluidphysics.mixin;

import de.lolhens.minecraft.fluidphysics.FluidPhysicsMod;
import de.lolhens.minecraft.fluidphysics.util.FluidSourceFinder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.PistonBlockStructureHelper;
import net.minecraft.block.material.PushReaction;
import net.minecraft.fluid.FlowingFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scala.Option;

import java.util.HashSet;
import java.util.Set;

@Mixin(PistonBlock.class)
public abstract class PistonBlockMixin {
    @Inject(at = @At("HEAD"), method = "canPush", cancellable = true)
    private static void canPush(BlockState state,
                                World world,
                                BlockPos pos,
                                Direction motionDir,
                                boolean canBreak,
                                Direction pistonDir,
                                CallbackInfoReturnable<Boolean> info) {
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty() &&
                FluidPhysicsMod.config().isEnabledFor(fluidState.getFluid()) &&
                fluidState.isSource()) {
            BlockPos nextBlockPos = pos.offset(motionDir);
            BlockState nextBlockState = world.getBlockState(nextBlockPos);
            if (!(nextBlockState.isAir(world, nextBlockPos) ||
                    nextBlockState.getFluidState().getFluid().isEquivalentTo(fluidState.getFluid()) ||
                    nextBlockState.getPushReaction() == PushReaction.DESTROY)) {
                info.setReturnValue(false);
            }
        }
    }

    @Inject(at = @At("HEAD"), method = "doMove", cancellable = true)
    private void doMove(World world,
                        BlockPos pos,
                        Direction dir,
                        boolean retract,
                        CallbackInfoReturnable<Boolean> info) {
        BlockPos blockPos = pos.offset(dir);
        if (!retract && world.getBlockState(blockPos).isIn(Blocks.PISTON_HEAD)) {
            world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), 20);
        }

        PistonBlockStructureHelper pistonHandler = new PistonBlockStructureHelper(world, pos, dir, retract);
        if (!pistonHandler.canMove()) {
            info.setReturnValue(false);
        } else {
            Direction oppositeDir = dir.getOpposite();

            Set<BlockPos> blockPosSet = new HashSet<>();
            blockPosSet.add(blockPos);
            for (BlockPos movedBlockPos : pistonHandler.getBlocksToMove()) {
                blockPosSet.add(movedBlockPos);
                blockPosSet.add(movedBlockPos.offset(dir));
            }

            for (BlockPos currentBlockPos : blockPosSet) {
                BlockState blockState = world.getBlockState(currentBlockPos);
                FluidState fluidState = blockState.getFluidState();

                if (!fluidState.isEmpty() &&
                        FluidPhysicsMod.config().isEnabledFor(fluidState.getFluid()) &&
                        fluidState.getFluid() instanceof FlowingFluid && !fluidState.isSource()) {
                    FlowingFluid fluid = (FlowingFluid) fluidState.getFluid();

                    Option<BlockPos> sourcePos = FluidSourceFinder.findSource(
                            world,
                            currentBlockPos,
                            fluidState.getFluid(),
                            oppositeDir,
                            FluidSourceFinder.setOf(blockPosSet),
                            true,
                            true
                    );

                    if (sourcePos.isDefined()) {
                        FluidState still = fluid.getStillFluidState(false);
                        FluidSourceFinder.moveSource(world, sourcePos.get(), currentBlockPos, blockState, fluid, still);
                    }
                }
            }
        }
    }
}
