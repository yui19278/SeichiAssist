package com.github.unchama.seichiassist.menus.skill

import cats.data.Kleisli
import cats.effect.concurrent.Ref
import cats.effect.{ConcurrentEffect, IO, SyncIO}
import com.github.unchama.concurrent.NonServerThreadContextShift
import com.github.unchama.generic.effect.concurrent.TryableFiber
import com.github.unchama.itemstackbuilder.{AbstractItemStackBuilder, IconItemStackBuilder, SkullItemStackBuilder, TippedArrowItemStackBuilder}
import com.github.unchama.menuinventory.router.CanOpen
import com.github.unchama.menuinventory.slot.button.action.{ButtonEffect, LeftClickButtonEffect}
import com.github.unchama.menuinventory.slot.button.{Button, RecomputedButton, ReloadingButton}
import com.github.unchama.menuinventory.{ChestSlotRef, Menu, MenuFrame, MenuSlotLayout}
import com.github.unchama.minecraft.actions.OnMinecraftServerThread
import com.github.unchama.seichiassist.SeichiAssist
import com.github.unchama.seichiassist.data.XYZTuple
import com.github.unchama.seichiassist.data.player.PlayerSkillState
import com.github.unchama.seichiassist.effects.unfocused.BroadcastSoundEffect
import com.github.unchama.seichiassist.menus.CommonButtons
import com.github.unchama.seichiassist.menus.stickmenu.FirstPage
import com.github.unchama.seichiassist.seichiskill.SeichiSkill.AssaultArmor
import com.github.unchama.seichiassist.seichiskill._
import com.github.unchama.seichiassist.seichiskill.assault.AssaultRoutine
import com.github.unchama.seichiassist.subsystems.breakcount.BreakCountAPI
import com.github.unchama.seichiassist.subsystems.discordnotification.DiscordNotificationAPI
import com.github.unchama.seichiassist.subsystems.mana.ManaApi
import com.github.unchama.seichiassist.util.Util
import com.github.unchama.targetedeffect.SequentialEffect
import com.github.unchama.targetedeffect.TargetedEffect.emptyEffect
import com.github.unchama.targetedeffect.commandsender.MessageEffect
import com.github.unchama.targetedeffect.player.FocusedSoundEffect
import org.bukkit.ChatColor._
import org.bukkit.entity.Player
import org.bukkit.potion.PotionType
import org.bukkit.{Material, Sound}

object ActiveSkillMenu extends Menu {

  private sealed trait SkillSelectionState

  private case object Locked extends SkillSelectionState

  private case object Unlocked extends SkillSelectionState

  private case object Selected extends SkillSelectionState

  import com.github.unchama.menuinventory.syntax._
  import com.github.unchama.seichiassist.concurrent.PluginExecutionContexts.{asyncShift, layoutPreparationContext}

  class Environment(implicit
                    val breakCountApi: BreakCountAPI[IO, SyncIO, Player],
                    val manaApi: ManaApi[IO, SyncIO, Player],
                    val ioCanOpenActiveSkillMenu: IO CanOpen ActiveSkillMenu.type,
                    val ioCanOpenActiveSkillEffectMenu: IO CanOpen ActiveSkillEffectMenu.type,
                    val ioCanOpenFirstPage: IO CanOpen FirstPage.type,
                    val ioOnMainThread: OnMinecraftServerThread[IO],
                    val globalNotification: DiscordNotificationAPI[IO])

  override val frame: MenuFrame = MenuFrame(5.chestRows, s"$DARK_PURPLE${BOLD}?????????????????????")

  private def skillStateRef(player: Player): IO[Ref[IO, PlayerSkillState]] =
    IO {
      SeichiAssist.playermap(player.getUniqueId).skillState
    }

  private def totalActiveSkillPoint(player: Player)(implicit environment: Environment): IO[Int] =
    environment
      .breakCountApi.seichiAmountDataRepository(player)
      .read
      .map { data =>
        val level = data.levelCorrespondingToExp.level
        (1 to level).map(i => (i.toDouble / 10.0).ceil.toInt).sum
      }
      .toIO

  private class ButtonComputations(player: Player)(implicit environment: Environment) {

    import environment._
    import player._

    val availableActiveSkillPoint: IO[Int] =
      for {
        ref <- skillStateRef(player)
        skillState <- ref.get
        totalPoint <- totalActiveSkillPoint(player)
      } yield {
        totalPoint - skillState.consumedActiveSkillPoint
      }

    val computeStatusButton: IO[Button] = RecomputedButton(
      for {
        ref <- skillStateRef(player)
        state <- ref.get
        availablePoints <- availableActiveSkillPoint
      } yield {
        val activeSkillSelectionLore: Option[String] =
          state.activeSkill.map(activeSkill =>
            s"$RESET${GREEN}???????????????????????????????????????????????????${activeSkill.name}"
          )

        val assaultSkillSelectionLore: Option[String] =
          state.assaultSkill.map { assaultSkill =>
            val heading =
              if (assaultSkill == SeichiSkill.AssaultArmor)
                s"$RESET${GREEN}????????????????????????????????????????????????"
              else
                s"$RESET${GREEN}??????????????????????????????????????????"

            s"$heading${assaultSkill.name}"
          }

        val itemStack =
          new SkullItemStackBuilder(getUniqueId)
            .title(s"$YELLOW$UNDERLINE$BOLD${getName}????????????????????????????????????")
            .lore(
              activeSkillSelectionLore.toList ++
                assaultSkillSelectionLore.toList ++
                List(s"$RESET${YELLOW}????????????????????????????????????????????????$availablePoints")
            )
            .build()
        Button(itemStack)
      }
    )

    def computeSkillButtonFor(skill: SeichiSkill)(implicit environment: Environment): IO[Button] = {
      for {
        ref <- skillStateRef(player)
        state <- ref.get
      } yield {
        val selectionState = ButtonComputations.selectionStateOf(skill)(state)
        import com.github.unchama.seichiassist.concurrent.PluginExecutionContexts.asyncShift
        implicit val concurrentEffect: ConcurrentEffect[IO] = IO.ioConcurrentEffect(asyncShift)
        ButtonComputations.seichiSkillButton(selectionState, skill)
      }
    }
  }

  private object ButtonComputations {
    def baseSkillIcon(skill: SeichiSkill): AbstractItemStackBuilder[Nothing] = {
      skill match {
        case skill: ActiveSkill =>
          skill match {
            case SeichiSkill.DualBreak =>
              new IconItemStackBuilder(Material.GRASS)
            case SeichiSkill.TrialBreak =>
              new IconItemStackBuilder(Material.STONE)
            case SeichiSkill.Explosion =>
              new IconItemStackBuilder(Material.COAL_ORE)
            case SeichiSkill.MirageFlare =>
              new IconItemStackBuilder(Material.IRON_ORE)
            case SeichiSkill.Dockarn =>
              new IconItemStackBuilder(Material.GOLD_ORE)
            case SeichiSkill.GiganticBomb =>
              new IconItemStackBuilder(Material.REDSTONE_ORE)
            case SeichiSkill.BrilliantDetonation =>
              new IconItemStackBuilder(Material.LAPIS_ORE)
            case SeichiSkill.LemuriaImpact =>
              new IconItemStackBuilder(Material.EMERALD_ORE)
            case SeichiSkill.EternalVice =>
              new IconItemStackBuilder(Material.DIAMOND_ORE)

            case SeichiSkill.TomBoy =>
              new IconItemStackBuilder(Material.SADDLE)
            case SeichiSkill.Thunderstorm =>
              new IconItemStackBuilder(Material.MINECART)
            case SeichiSkill.StarlightBreaker =>
              new IconItemStackBuilder(Material.STORAGE_MINECART)
            case SeichiSkill.EarthDivide =>
              new IconItemStackBuilder(Material.POWERED_MINECART)
            case SeichiSkill.HeavenGaeBolg =>
              new IconItemStackBuilder(Material.EXPLOSIVE_MINECART)
            case SeichiSkill.Decision =>
              new IconItemStackBuilder(Material.HOPPER_MINECART)

            case SeichiSkill.EbifriDrive =>
              new TippedArrowItemStackBuilder(PotionType.REGEN)
            case SeichiSkill.HolyShot =>
              new TippedArrowItemStackBuilder(PotionType.FIRE_RESISTANCE)
            case SeichiSkill.TsarBomba =>
              new TippedArrowItemStackBuilder(PotionType.INSTANT_HEAL)
            case SeichiSkill.ArcBlast =>
              new TippedArrowItemStackBuilder(PotionType.NIGHT_VISION)
            case SeichiSkill.PhantasmRay =>
              new TippedArrowItemStackBuilder(PotionType.SPEED)
            case SeichiSkill.Supernova =>
              new TippedArrowItemStackBuilder(PotionType.INSTANT_DAMAGE)
          }
        case skill: AssaultSkill =>
          skill match {
            case SeichiSkill.WhiteBreath =>
              new IconItemStackBuilder(Material.SNOW_BLOCK)
            case SeichiSkill.AbsoluteZero =>
              new IconItemStackBuilder(Material.ICE)
            case SeichiSkill.DiamondDust =>
              new IconItemStackBuilder(Material.PACKED_ICE)
            case SeichiSkill.LavaCondensation =>
              new IconItemStackBuilder(Material.NETHERRACK)
            case SeichiSkill.MoerakiBoulders =>
              new IconItemStackBuilder(Material.NETHER_BRICK)
            case SeichiSkill.Eldfell =>
              new IconItemStackBuilder(Material.MAGMA)
            case SeichiSkill.VenderBlizzard =>
              new IconItemStackBuilder(Material.NETHER_STAR)
            case SeichiSkill.AssaultArmor =>
              new IconItemStackBuilder(Material.DIAMOND_CHESTPLATE)
          }
      }
    }

    def prerequisiteSkillName(skill: SeichiSkill): String =
      SkillDependency.prerequisites(skill)
        .headOption.map(_.name)
        .getOrElse("??????")

    def breakRangeDescription(range: SkillRange): String = {
      val colorPrefix = s"$RESET$GREEN"
      val description = range match {
        case range: ActiveSkillRange =>
          range match {
            case ActiveSkillRange.MultiArea(effectChunkSize, areaCount) =>
              val XYZTuple(x, y, z) = effectChunkSize
              val count = if (areaCount > 1) s" x$areaCount" else ""
              s"${x}x${y}x${z}??????????????????" + count

            case ActiveSkillRange.RemoteArea(effectChunkSize) =>
              val XYZTuple(x, y, z) = effectChunkSize
              s"???${x}x${y}x${z}??????????????????"
          }
        case range: AssaultSkillRange =>
          val XYZTuple(x, y, z) = range.effectChunkSize
          range match {
            case AssaultSkillRange.Armor(_) =>
              s"?????????????????????${x}x${y}x${z}??????????????????"
            case AssaultSkillRange.Lava(_) =>
              s"???????????????${x}x${y}x${z}???????????????????????????"
            case AssaultSkillRange.Liquid(_) =>
              s"????????????/??????${x}x${y}x${z}???????????????????????????"
            case AssaultSkillRange.Water(_) =>
              s"????????????${x}x${y}x${z}??????????????????????????????"
          }
      }

      colorPrefix + description
    }

    def coolDownDescription(skill: SeichiSkill): String = {
      val colorPrefix = s"$RESET$DARK_GRAY"
      val coolDownAmount = skill.maxCoolDownTicks.map { ticks =>
        String.format("%.2f", ticks * 50 / 1000.0)
      }.getOrElse("??????")

      colorPrefix + coolDownAmount
    }

    def selectionStateOf(skill: SeichiSkill)(skillState: PlayerSkillState): SkillSelectionState = {
      if (skillState.obtainedSkills.contains(skill)) {
        val selected = skill match {
          case skill: ActiveSkill =>
            skillState.activeSkill.contains(skill)
          case skill: AssaultSkill =>
            skillState.assaultSkill.contains(skill)
        }

        if (selected) Selected else Unlocked
      } else {
        Locked
      }
    }

    def seichiSkillButton[
      F[_] : ConcurrentEffect : NonServerThreadContextShift : DiscordNotificationAPI
    ](state: SkillSelectionState, skill: SeichiSkill)
     (implicit environment: Environment): Button = {
      import environment._

      val itemStack = {
        val base = state match {
          case Locked =>
            new IconItemStackBuilder(Material.BEDROCK)
          case Selected | Unlocked =>
            baseSkillIcon(skill)
        }

        val clickEffectDescription: List[String] = state match {
          case Locked =>
            val requiredPointDescription =
              s"$RESET${YELLOW}?????????????????????????????????????????????${skill.requiredActiveSkillPoint}"

            val defaultDescription =
              List(
                requiredPointDescription,
                s"$RESET${DARK_RED}??????????????????${prerequisiteSkillName(skill)}",
                s"$RESET$AQUA${UNDERLINE}?????????????????????"
              )

            skill match {
              case skill: AssaultSkill => skill match {
                case SeichiSkill.VenderBlizzard =>
                  List(
                    requiredPointDescription,
                    s"$RESET${DARK_RED}?????????/??????????????????????????????????????????????????????????????????????????????",
                    s"$RESET${DARK_RED}??????????????????????????????????????????????????????",
                    s"$RESET$AQUA${UNDERLINE}?????????????????????"
                  )
                case SeichiSkill.AssaultArmor =>
                  List(s"$RESET${YELLOW}??????????????????????????????????????????????????????")
                case _ => defaultDescription
              }
              case _: ActiveSkill => defaultDescription
            }
          case Unlocked => List(s"$RESET$DARK_RED${UNDERLINE}????????????????????????")
          case Selected => List(s"$RESET$DARK_RED${UNDERLINE}???????????????????????????")
        }

        base
          .title(s"$RED$UNDERLINE$BOLD${skill.name}")
          .lore(
            List(
              s"$RESET$GREEN${breakRangeDescription(skill.range)}",
              s"$RESET${DARK_GRAY}?????????????????????${coolDownDescription(skill)}",
              s"$RESET${BLUE}???????????????${skill.manaCost}",
            ) ++ clickEffectDescription
          )

        if (state == Selected) base.enchanted()

        base.build()
      }

      val effect: ButtonEffect = LeftClickButtonEffect(Kleisli { player =>
        for {
          // ?????????????????????????????????????????????????????????totalPoints???????????????
          totalPoints <- totalActiveSkillPoint(player)
          playerSkillStateRef <- skillStateRef(player)

          feedbackEffect <- playerSkillStateRef.modify { skillState =>
            selectionStateOf(skill)(skillState) match {
              case Locked =>
                val availablePoints = totalPoints - skillState.consumedActiveSkillPoint

                if (availablePoints >= skill.requiredActiveSkillPoint)
                  skillState.lockedDependency(skill) match {
                    case None =>
                      val unlockedState = skillState.obtained(skill)
                      val (newState, assaultSkillUnlockEffects) =
                        if (!unlockedState.obtainedSkills.contains(AssaultArmor) &&
                          unlockedState.lockedDependency(SeichiSkill.AssaultArmor).isEmpty) {

                          val notificationMessage = s"${player.getName}???????????????????????????????????????????????????????????????????????????????????????"

                          import cats.effect.implicits._

                          (
                            unlockedState.obtained(SeichiSkill.AssaultArmor),
                            SequentialEffect(
                              MessageEffect(s"$YELLOW${BOLD}?????????????????????????????????????????????????????????????????????????????????"),
                              // ?????????????????????????????????????????????????????????????????????????????????
                              Kleisli.liftF(DiscordNotificationAPI[F].send(notificationMessage.replaceAllLiterally("_", "\\_")).toIO),
                              Kleisli.liftF(IO {
                                Util.sendMessageToEveryoneIgnoringPreference(s"$GOLD$BOLD$notificationMessage")
                              }),
                              BroadcastSoundEffect(Sound.ENTITY_ENDERDRAGON_DEATH, 1.0f, 1.2f),
                            )
                          )
                        } else (unlockedState, emptyEffect)

                      (
                        newState,
                        SequentialEffect(
                          FocusedSoundEffect(Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f),
                          MessageEffect(s"$AQUA$BOLD${skill.name}?????????????????????"),
                          assaultSkillUnlockEffects
                        )
                      )
                    case Some(locked) =>
                      (
                        skillState,
                        SequentialEffect(
                          FocusedSoundEffect(Sound.BLOCK_GLASS_PLACE, 1.0f, 0.1f),
                          MessageEffect(s"${DARK_RED}???????????????[${locked.name}]????????????????????????????????????")
                        )
                      )
                  }
                else
                  (
                    skillState,
                    SequentialEffect(
                      FocusedSoundEffect(Sound.BLOCK_GLASS_PLACE, 1.0f, 0.1f),
                      MessageEffect(s"${DARK_RED}??????????????????????????????????????????????????????")
                    )
                  )
              case Unlocked =>
                val skillType =
                  skill match {
                    case _: ActiveSkill => "????????????????????????"
                    case _: AssaultSkill => "?????????????????????"
                  }

                (
                  skillState.select(skill),
                  SequentialEffect(
                    skill match {
                      case skill: AssaultSkill =>
                        import cats.implicits._
                        import com.github.unchama.seichiassist.concurrent.PluginExecutionContexts.sleepAndRoutineContext
                        import environment.manaApi

                        val tryStartRoutine = TryableFiber.start(AssaultRoutine.tryStart(player, skill))
                        val fiberRepository = SeichiAssist.instance.assaultSkillRoutines
                        val tryStart =
                          fiberRepository(player).stopAnyFiber >>
                            fiberRepository(player).flipState(tryStartRoutine).as(())

                        Kleisli.liftF(tryStart)
                      case _ => emptyEffect
                    },
                    FocusedSoundEffect(Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f, 0.1f),
                    MessageEffect(s"$GREEN$skillType???${skill.name} ????????????????????????")
                  )
                )
              case Selected =>
                (
                  skillState.deselect(skill),
                  SequentialEffect(
                    FocusedSoundEffect(Sound.BLOCK_GLASS_PLACE, 1.0f, 0.1f),
                    MessageEffect(s"${YELLOW}???????????????????????????")
                  )
                )
            }
          }
          _ <- feedbackEffect.run(player)
        } yield ()
      })

      ReloadingButton(ActiveSkillMenu)(Button(itemStack, effect))
    }
  }

  private object ConstantButtons {
    def skillEffectMenuButton(implicit
                              ioCanOpenActiveSkillEffectMenu: IO CanOpen ActiveSkillEffectMenu.type,
                              ioOnMainThread: OnMinecraftServerThread[IO]): Button = {
      Button(
        new IconItemStackBuilder(Material.BOOKSHELF)
          .title(s"$UNDERLINE$BOLD${LIGHT_PURPLE}??????????????????")
          .lore(
            s"$RESET${GRAY}????????????????????????????????????????????????",
            s"$RESET$UNDERLINE${DARK_RED}????????????????????????????????????",
          )
          .build(),
        LeftClickButtonEffect(
          FocusedSoundEffect(Sound.BLOCK_BREWING_STAND_BREW, 1f, 0.5f),
          ioCanOpenActiveSkillEffectMenu.open(ActiveSkillEffectMenu)
        )
      )
    }

    def resetSkillsButton(implicit environment: Environment): Button = {
      import environment._

      ReloadingButton(ActiveSkillMenu) {
        Button(
          new IconItemStackBuilder(Material.GLASS)
            .title(s"$UNDERLINE$BOLD${YELLOW}???????????????????????????")
            .lore(s"$RESET$UNDERLINE${DARK_RED}????????????????????????")
            .build(),
          LeftClickButtonEffect(
            Kleisli { p =>
              for {
                ref <- skillStateRef(p)
                _ <- ref.update(_.deselected())
              } yield ()
            },
            MessageEffect(s"${YELLOW}????????????????????????????????????????????????"),
            FocusedSoundEffect(Sound.BLOCK_GLASS_PLACE, 1.0f, 0.1f)
          )
        )
      }
    }
  }

  override def computeMenuLayout(player: Player)(implicit environment: Environment): IO[MenuSlotLayout] = {
    import cats.implicits._
    import environment._
    import eu.timepit.refined.auto._

    val buttonComputations = new ButtonComputations(player)
    import ConstantButtons._
    import buttonComputations._

    val constantPart = Map(
      ChestSlotRef(0, 1) -> resetSkillsButton,
      ChestSlotRef(0, 2) -> skillEffectMenuButton,
      ChestSlotRef(4, 0) -> CommonButtons.openStickMenu
    )

    import SeichiSkill._

    val dynamicPartComputation = List(
      ChestSlotRef(0, 0) -> computeStatusButton,

      ChestSlotRef(0, 3) -> computeSkillButtonFor(EbifriDrive),
      ChestSlotRef(0, 4) -> computeSkillButtonFor(HolyShot),
      ChestSlotRef(0, 5) -> computeSkillButtonFor(TsarBomba),
      ChestSlotRef(0, 6) -> computeSkillButtonFor(ArcBlast),
      ChestSlotRef(0, 7) -> computeSkillButtonFor(PhantasmRay),
      ChestSlotRef(0, 8) -> computeSkillButtonFor(Supernova),

      ChestSlotRef(1, 3) -> computeSkillButtonFor(TomBoy),
      ChestSlotRef(1, 4) -> computeSkillButtonFor(Thunderstorm),
      ChestSlotRef(1, 5) -> computeSkillButtonFor(StarlightBreaker),
      ChestSlotRef(1, 6) -> computeSkillButtonFor(EarthDivide),
      ChestSlotRef(1, 7) -> computeSkillButtonFor(HeavenGaeBolg),
      ChestSlotRef(1, 8) -> computeSkillButtonFor(Decision),

      ChestSlotRef(2, 0) -> computeSkillButtonFor(DualBreak),
      ChestSlotRef(2, 1) -> computeSkillButtonFor(TrialBreak),
      ChestSlotRef(2, 2) -> computeSkillButtonFor(Explosion),
      ChestSlotRef(2, 3) -> computeSkillButtonFor(MirageFlare),
      ChestSlotRef(2, 4) -> computeSkillButtonFor(Dockarn),
      ChestSlotRef(2, 5) -> computeSkillButtonFor(GiganticBomb),
      ChestSlotRef(2, 6) -> computeSkillButtonFor(BrilliantDetonation),
      ChestSlotRef(2, 7) -> computeSkillButtonFor(LemuriaImpact),
      ChestSlotRef(2, 8) -> computeSkillButtonFor(EternalVice),

      ChestSlotRef(3, 3) -> computeSkillButtonFor(WhiteBreath),
      ChestSlotRef(3, 4) -> computeSkillButtonFor(AbsoluteZero),
      ChestSlotRef(3, 5) -> computeSkillButtonFor(DiamondDust),

      ChestSlotRef(4, 3) -> computeSkillButtonFor(LavaCondensation),
      ChestSlotRef(4, 4) -> computeSkillButtonFor(MoerakiBoulders),
      ChestSlotRef(4, 5) -> computeSkillButtonFor(Eldfell),

      ChestSlotRef(4, 7) -> computeSkillButtonFor(VenderBlizzard),
      ChestSlotRef(4, 8) -> computeSkillButtonFor(AssaultArmor),
    )
      .map(_.sequence)
      .sequence

    for {
      dynamicPart <- dynamicPartComputation
    } yield MenuSlotLayout(constantPart ++ dynamicPart)
  }
}
