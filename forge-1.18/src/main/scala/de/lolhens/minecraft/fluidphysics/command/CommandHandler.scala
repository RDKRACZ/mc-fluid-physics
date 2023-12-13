package de.lolhens.minecraft.fluidphysics.command

import cats.syntax.either._
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands._
import net.minecraft.network.chat.{BaseComponent, TextComponent}
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent.Phase
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.event.{RegisterCommandsEvent, TickEvent}

import java.util.UUID

object CommandHandler {
  implicit def literalText(string: String): BaseComponent = new TextComponent(string)

  val typeConfirm = "type '/fluidphysics confirm' to continue"

  @volatile private var confirmationPending: Map[UUID, Command] = Map.empty
  @volatile private var ticking: Set[(UUID, Command)] = Set.empty

  trait Command {
    def source: CommandSourceStack

    def run(): Either[String, Unit]

    protected final def setTicking(value: Boolean): Unit = CommandHandler.synchronized {
      if (value)
        ticking += (source.getPlayerOrException.getUUID -> this)
      else
        ticking -= (source.getPlayerOrException.getUUID -> this)
    }

    def tick(world: Level): Unit

    def cancel(world: Option[Level]): Unit
  }

  def addPending(player: Player, command: Command): Unit =
    confirmationPending += (player.getUUID -> command)

  private def isOperator(source: CommandSourceStack): Boolean =
    source.getServer.getPlayerList.isOp(source.getPlayerOrException.getGameProfile)

  private def commandResult(either: Either[String, Unit])
                           (implicit context: CommandContext[CommandSourceStack]): Int = either match {
    case Right(_) => 1
    case Left(error) =>
      context.getSource.sendFailure(error)
      -1
  }

  private def mustBeOperator(f: => Either[String, Unit])
                            (implicit context: CommandContext[CommandSourceStack]): Either[String, Unit] =
    if (isOperator(context.getSource)) f
    else Left("Player must be operator!")

  def init(): Unit = {
    MinecraftForge.EVENT_BUS.addListener { event: TickEvent.WorldTickEvent =>
      (event.phase, event.world) match {
        case (Phase.END, serverWorld: ServerLevel) =>
          ticking.foreach(_._2.tick(serverWorld))

        case _ =>
      }
    }

    def cancelAll(world: Level): Unit = {
      ticking.foreach(_._2.cancel(Some(world)))
    }

    MinecraftForge.EVENT_BUS.addListener { event: WorldEvent.Unload =>
      cancelAll(event.getWorld.asInstanceOf[Level])
      println("UNLOAD")
    }

    MinecraftForge.EVENT_BUS.addListener { registerCommandsEvent: RegisterCommandsEvent =>
      registerCommandsEvent.getDispatcher.register(
        literal("fluidphysics")
          .`then`(literal("removelayers").executes(implicit context => commandResult {
            mustBeOperator {
              RemoveLayersCommand.execute(context)
            }
          }))
          .`then`(literal("confirm").executes(implicit context => commandResult {
            mustBeOperator {
              val player = context.getSource.getPlayerOrException
              confirmationPending.get(player.getUUID)
                .toRight("No pending command!")
                .map { pending =>
                  confirmationPending -= player.getUUID
                  pending.run()
                }
            }
          }))
          .`then`(literal("cancel").executes(implicit context => commandResult {
            mustBeOperator {
              val player = context.getSource.getPlayerOrException
              confirmationPending.get(player.getUUID)
                .toRight("No pending command!")
                .map { _ =>
                  confirmationPending -= player.getUUID
                  context.getSource.sendSuccess("Pending command cancelled", false)
                }
                .leftFlatMap { left =>
                  Some(ticking.filter(_._1 == player.getUUID))
                    .filter(_.nonEmpty)
                    .toRight(left)
                    .map(_.foreach(_._2.cancel(None)))
                }
            }
          }))
      )
    }
  }
}
