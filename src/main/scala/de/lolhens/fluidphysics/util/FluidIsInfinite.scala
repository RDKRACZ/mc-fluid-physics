package de.lolhens.fluidphysics.util

import de.lolhens.fluidphysics.FluidPhysicsMod
import net.minecraft.fluid.Fluid
import net.minecraft.util.math.{BlockPos, Direction}
import net.minecraft.world.WorldView

import scala.jdk.CollectionConverters._

object FluidIsInfinite {
  private val localWorld: ThreadLocal[WorldView] = new ThreadLocal()
  private val localPos: ThreadLocal[BlockPos] = new ThreadLocal()

  def set(world: WorldView, pos: BlockPos): Unit = {
    localWorld.set(world);
    localPos.set(pos)
  }

  def world: WorldView = localWorld.get()

  def pos: BlockPos = localPos.get()

  private val horizontal: Array[Direction] = Direction.Type.HORIZONTAL.iterator().asScala.toArray

  def isInfinite(fluid: Fluid): Boolean =
    if (FluidPhysicsMod.enabledFor(fluid)) {
      val nextToSpring = FluidPhysicsMod.springBlock match {
        case Some(springBlock) if FluidPhysicsMod.springAllowsInfiniteWater =>
          (Direction.DOWN +: horizontal).exists { direction =>
            world.getBlockState(pos.offset(direction)).isOf(springBlock)
          }

        case _ =>
          false
      }

      nextToSpring
    } else {
      true
    }
}
