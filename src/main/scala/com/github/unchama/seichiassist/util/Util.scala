package com.github.unchama.seichiassist.util

import cats.data.Kleisli
import cats.effect.{IO, SyncIO}
import cats.{Monad, data}
import com.github.unchama.minecraft.actions.{GetConnectedPlayers, OnMinecraftServerThread}
import com.github.unchama.minecraft.bukkit.actions.GetConnectedBukkitPlayers
import com.github.unchama.seichiassist.concurrent.PluginExecutionContexts
import com.github.unchama.seichiassist.concurrent.PluginExecutionContexts.onMainThread
import com.github.unchama.seichiassist.minestack.MineStackObj
import com.github.unchama.seichiassist.{DefaultEffectEnvironment, MineStackObjectList, SeichiAssist}
import com.github.unchama.util.bukkit.ItemStackUtil
import enumeratum._
import org.bukkit.ChatColor._
import org.bukkit._
import org.bukkit.block.{Block, Skull}
import org.bukkit.entity.EntityType._
import org.bukkit.entity.{EntityType, Firework, LivingEntity, Player}
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.inventory.{ItemFlag, ItemStack, PlayerInventory}

import java.text.SimpleDateFormat
import java.util.stream.IntStream
import java.util.{Calendar, Random}

object Util {

  import scala.jdk.CollectionConverters._
  import scala.util.chaining._

  private val types = List(FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.BURST, FireworkEffect.Type.CREEPER, FireworkEffect.Type.STAR)

  def sendPlayerDataNullMessage(player: Player): Unit = {
    player.sendMessage(RED.toString + "初回ログイン時の読み込み中か、読み込みに失敗しています")
    player.sendMessage(RED.toString + "再接続しても改善されない場合はお問い合わせフォームまたは整地鯖公式Discordサーバーからお知らせ下さい")
  }

  @deprecated("please use ManagedWorld#isSeichiSkillAllowed")
  def seichiSkillsAllowedIn(world: World): Boolean = {
    val seichiWorldPrefix = if (SeichiAssist.DEBUG) SeichiAssist.DEBUGWORLDNAME else SeichiAssist.SEICHIWORLDNAME
    val worldNameLowerCase = world.getName.toLowerCase()

    worldNameLowerCase match {
      case "world_sw_zero" => false // 整地ワールドzeroではスキル発動不可
      case "world" |
           "world_2" |
           "world_nether" |
           "world_the_end" |
           "world_TT" |
           "world_nether_TT" |
           "world_the_end_TT" => true
      case _ => worldNameLowerCase.startsWith(seichiWorldPrefix)
    }
  }

  /**
   * プレイヤーが整地ワールドにいるかどうかの判定処理(整地ワールド=true、それ以外=false)
   *
   * @deprecated use ManagedWorld
   */
  @Deprecated()
  def isSeichiWorld(player: Player): Boolean = {
    //デバッグモード時は全ワールドtrue(DEBUGWORLDNAME = worldの場合)
    var worldname = SeichiAssist.SEICHIWORLDNAME
    if (SeichiAssist.DEBUG) {
      worldname = SeichiAssist.DEBUGWORLDNAME
    }
    //整地ワールドではtrue
    player.getWorld.getName.toLowerCase().startsWith(worldname)
  }

  /**
   * プレイヤーに安全にアイテムを付与します。
   *
   * @param player    付与する対象プレイヤー
   * @param itemStack 付与するアイテム
   * @deprecated use [[grantItemStacksEffect]]
   */
  @deprecated def addItemToPlayerSafely(player: Player, itemStack: ItemStack): Unit = {
    // Javaから呼ばれているのでimplicitが使いづらい　grantItemStacksEffectに置き換えたい
    import PluginExecutionContexts.onMainThread

    DefaultEffectEnvironment.unsafeRunEffectAsync(
      "アイテムスタックを付与する",
      grantItemStacksEffect(itemStack).run(player)
    )
  }

  /**
   * プレイヤーに複数のアイテムを一度に付与する。
   * インベントリに入り切らなかったアイテムはプレーヤーの立ち位置にドロップされる。
   *
   * @param itemStacks 付与するアイテム
   */
  def grantItemStacksEffect[F[_] : OnMinecraftServerThread](itemStacks: ItemStack*): Kleisli[F, Player, Unit] =
    data.Kleisli { player =>
      val amalgamated = ItemStackUtil.amalgamate(itemStacks).filter(_.getType != Material.AIR)

      OnMinecraftServerThread[F].runAction(SyncIO {
        player.getInventory
          .addItem(amalgamated: _*)
          .values().asScala
          .filter(_.getType != Material.AIR)
          .foreach(dropItem(player, _))
      })
    }

  //プレイヤーのインベントリがフルかどうか確認
  def isPlayerInventoryFull(player: Player): Boolean = player.getInventory.firstEmpty() == -1

  //指定されたアイテムを指定されたプレイヤーにドロップする
  def dropItem(player: Player, itemstack: ItemStack): Unit = {
    player.getWorld.dropItemNaturally(player.getLocation, itemstack)
  }

  //指定されたアイテムを指定されたプレイヤーインベントリに追加する
  def addItem(player: Player, itemstack: ItemStack): Unit = {
    player.getInventory.addItem(itemstack)
  }

  def sendMessageToEveryoneIgnoringPreference[T](content: T)
                                                (implicit send: PlayerSendable[T, IO]): Unit = {
    implicit val g: GetConnectedBukkitPlayers[IO] = new GetConnectedBukkitPlayers[IO]

    sendMessageToEveryoneIgnoringPreferenceM[T, IO](content).unsafeRunAsyncAndForget()
  }

  def sendMessageToEveryoneIgnoringPreferenceM[
    T, F[_] : Monad : GetConnectedPlayers[*[_], Player]
  ](content: T)(implicit ev: PlayerSendable[T, F]): F[Unit] = {
    import cats.implicits._

    for {
      players <- GetConnectedPlayers[F, Player].now
      _ <- players.traverse(ev.send(_, content))
    } yield ()
  }

  def sendMessageToEveryone[T](content: T)
                              (implicit ev: PlayerSendable[T, IO]): Unit = {
    import cats.implicits._

    Bukkit.getOnlinePlayers.asScala.map { player =>
      for {
        playerSettings <- SeichiAssist.playermap(player.getUniqueId).settings.getBroadcastMutingSettings
        _ <- IO { if (!playerSettings.shouldMuteMessages) ev.send(player, content) }
      } yield ()
    }.toList.sequence.unsafeRunSync()
  }

  def getEnchantName(vaname: String, enchlevel: Int): String = {
    val levelLessEnchantmentMapping = Map(
      "WATER_WORKER" -> "水中採掘",
      "SILK_TOUCH" -> "シルクタッチ",
      "ARROW_FIRE" -> "フレイム",
      "ARROW_INFINITE" -> "無限",
      "MENDING" -> "修繕"
    )
    val leveledEnchantmentMapping = Map(
      "PROTECTION_ENVIRONMENTAL" -> "ダメージ軽減",
      "PROTECTION_FIRE" -> "火炎耐性",
      "PROTECTION_FALL" -> "落下耐性",
      "PROTECTION_EXPLOSIONS" -> "爆発耐性",
      "PROTECTION_PROJECTILE" -> "飛び道具耐性",
      "OXYGEN" -> "水中呼吸",
      "THORNS" -> "棘の鎧",
      "DEPTH_STRIDER" -> "水中歩行",
      "FROST_WALKER" -> "氷渡り",
      "DAMAGE_ALL" -> "ダメージ増加",
      "DAMAGE_UNDEAD" -> "アンデッド特効",
      "DAMAGE_ARTHROPODS" -> "虫特効",
      "KNOCKBACK" -> "ノックバック",
      "FIRE_ASPECT" -> "火属性",
      "LOOT_BONUS_MOBS" -> "ドロップ増加",
      "DIG_SPEED" -> "効率強化",
      "DURABILITY" -> "耐久力",
      "LOOT_BONUS_BLOCKS" -> "幸運",
      "ARROW_DAMAGE" -> "射撃ダメージ増加",
      "ARROW_KNOCKBACK" -> "パンチ",
      "LUCK" -> "宝釣り",
      "LURE" -> "入れ食い"
    )
    val enchantmentLevelRepresentation = getEnchantLevelRome(enchlevel)

    levelLessEnchantmentMapping.get(vaname).orElse(
      leveledEnchantmentMapping.get(vaname)
        .map(localizedName => s"$localizedName $enchantmentLevelRepresentation")
    ).getOrElse(vaname)
  }

  private def getEnchantLevelRome(enchantlevel: Int): String = {
    enchantlevel match {
      case 1 => "Ⅰ"
      case 2 => "Ⅱ"
      case 3 => "Ⅲ"
      case 4 => "Ⅳ"
      case 5 => "Ⅴ"
      case 6 => "Ⅵ"
      case 7 => "Ⅶ"
      case 8 => "Ⅷ"
      case 9 => "Ⅸ"
      case 10 => "Ⅹ"
      case _ => enchantlevel.toString
    }

  }

  def getDescFormat(list: List[String]): String = s" ${list.mkString("", "\n", "\n")}"

  def sendEverySound(kind: Sound, volume: Float, pitch: Float): Unit = {
    Bukkit.getOnlinePlayers.forEach(player =>
      player.playSound(player.getLocation, kind, volume, pitch)
    )
  }

  def sendEverySoundWithoutIgnore(kind: Sound, volume: Float, pitch: Float): Unit = {
    import cats.implicits._

    Bukkit.getOnlinePlayers.asScala.toList.map { player =>
      for {
        settings <- SeichiAssist.playermap(player.getUniqueId).settings.getBroadcastMutingSettings
        _ <- IO {
          if (!settings.shouldMuteSounds) player.playSound(player.getLocation, kind, volume, pitch)
        }
      } yield ()
    }.sequence.unsafeRunSync()
  }

  def getName(name: String): String = {
    //小文字にしてるだけだよ
    name.toLowerCase()
  }

  //指定された場所に花火を打ち上げる関数
  def launchFireWorks(loc: Location): Unit = {
    // 花火を作る
    val firework = loc.getWorld.spawn(loc, classOf[Firework])

    // 花火の設定情報オブジェクトを取り出す
    val meta = firework.getFireworkMeta
    val effect = FireworkEffect.builder()
    val rand = new Random()

    // 形状をランダムに決める
    effect.`with`(types(rand.nextInt(types.size)))

    // 基本の色を単色～5色以内でランダムに決める
    effect.withColor(getRandomColors(1 + rand.nextInt(5)): _*)

    // 余韻の色を単色～3色以内でランダムに決める
    effect.withFade(getRandomColors(1 + rand.nextInt(3)): _*)

    // 爆発後に点滅するかをランダムに決める
    effect.flicker(rand.nextBoolean())

    // 爆発後に尾を引くかをランダムに決める
    effect.trail(rand.nextBoolean())

    // 打ち上げ高さを1以上4以内でランダムに決める
    meta.setPower(1 + rand.nextInt(4))

    // 花火の設定情報を花火に設定
    meta.addEffect(effect.build())

    firework.setFireworkMeta(meta)
  }

  //カラーをランダムで決める
  def getRandomColors(length: Int): Array[Color] = {
    // 配列を作る
    val rand = new Random()
    // 配列の要素を順に処理していく
    // 24ビットカラーの範囲でランダムな色を決める

    // 配列を返す
    (0 until length).map { _ => Color.fromBGR(rand.nextInt(1 << 24)) }.toArray
  }

  //ガチャアイテムを含んでいるか調べる
  def containsGachaTicket(player: Player): Boolean = {
    player.getInventory.getStorageContents.exists(isGachaTicket)

    false
  }

  def isGachaTicket(itemStack: ItemStack): Boolean = {
    val containsRightClickMessage: String => Boolean = _.contains(s"${GREEN}右クリックで使えます")

    if (itemStack.getType != Material.SKULL_ITEM) return false

    val skullMeta = itemStack.getItemMeta.asInstanceOf[SkullMeta]

    if (!(skullMeta.hasOwner && skullMeta.getOwner == "unchama")) return false

    skullMeta.hasLore && skullMeta.getLore.asScala.exists(containsRightClickMessage)
  }

  def removeItemfromPlayerInventory(inventory: PlayerInventory,
                                    itemstack: ItemStack, count: Int): Boolean = {
    //持っているアイテムを減らす処理
    if (itemstack.getAmount == count) {
      // アイテムをcount個使うので、プレイヤーの手を素手にする
      inventory.setItemInMainHand(new ItemStack(Material.AIR))
      true
    } else if (itemstack.getAmount > count) {
      // プレイヤーが持っているアイテムをcount個減らす
      itemstack.setAmount(itemstack.getAmount - count)
      true
    } else false
  }

  def itemStackContainsOwnerName(itemstack: ItemStack, name: String): Boolean = {
    val meta = itemstack.getItemMeta

    val lore: List[String] =
      if (meta.hasLore)
        meta.getLore.asScala.toList
      else
        Nil

    lore.exists(line =>
      line.contains("所有者：") && line.drop(line.indexOf("所有者：") + 4).toLowerCase == name.toLowerCase()
    )
  }

  /**
   * GUIメニューアイコン作成用
   *
   * @author karayuu
   * @param material    メニューアイコンMaterial
   * @param amount      メニューアイコンのアイテム個数
   * @param displayName メニューアイコンのDisplayName
   * @param lore        メニューアイコンのLore
   * @param isHideFlags 攻撃値・ダメージ値を隠すかどうか(true: 隠す / false: 隠さない)
   * @return ItemStack型のメニューアイコン
   */
  def getMenuIcon(material: Material, amount: Int,
                  displayName: String, lore: List[String], isHideFlags: Boolean): ItemStack = {
    new ItemStack(material, amount).tap { itemStack =>
      import itemStack._
      setItemMeta {
        getItemMeta.tap { meta =>
          import meta._
          setDisplayName(displayName)
          setLore(lore.asJava)
          if (isHideFlags) {
            addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
          }
        }
      }
    }
  }

  /**
   * GUIメニューアイコン作成用
   *
   * @author karayuu
   * @param material    メニューアイコンMaterial, not `null`
   * @param amount      メニューアイコンのアイテム個数
   * @param durabity    メニューアイコンのダメージ値
   * @param displayName メニューアイコンのDisplayName, not `null`
   * @param lore        メニューアイコンのLore, not `null`
   * @param isHideFlags 攻撃値・ダメージ値を隠すかどうか(true: 隠す / false: 隠さない)
   * @throws IllegalArgumentException Material,DisplayName, Loreのいずれかが `null` の時
   * @return ItemStack型のメニューアイコン
   */
  def getMenuIcon(material: Material, amount: Int, durabity: Int,
                  displayName: String, lore: List[String], isHideFlags: Boolean): ItemStack = {
    new ItemStack(material, amount, durabity.toShort).tap { itemStack =>
      import itemStack._
      setItemMeta {
        getItemMeta.tap { meta =>
          import meta._
          setDisplayName(displayName)
          setLore(lore.asJava)

          if (isHideFlags) addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
      }
    }
  }

  def getPlayerDirection(player: Player): Direction = {
    var rotation = ((player.getLocation.getYaw + 180) % 360).toDouble

    if (rotation < 0) rotation += 360.0

    //0,360:south 90:west 180:north 270:east
    if (0.0 <= rotation && rotation < 45.0) Direction.NORTH
    else if (45.0 <= rotation && rotation < 135.0) Direction.EAST
    else if (135.0 <= rotation && rotation < 225.0) Direction.SOUTH
    else if (225.0 <= rotation && rotation < 315.0) Direction.WEST
    else Direction.NORTH
  }

  def showTime(cal: Calendar): String = {
    val date = cal.getTime
    val format = new SimpleDateFormat("yyyy/MM/dd HH:mm")
    format.format(date)
  }

  def showHour(cal: Calendar): String = {
    val date = cal.getTime
    val format = new SimpleDateFormat("HH:mm")
    format.format(date)
  }

  def getTimeZone(cal: Calendar): String = {
    val date = cal.getTime
    val format = new SimpleDateFormat("HH")
    val n = Integer.parseInt(format.format(date))
    if (4 <= n && n < 10)
      "morning"
    else if (10 <= n && n < 18)
      "day"
    else
      "night"
  }

  def isVotingFairyPeriod(start: Calendar, end: Calendar): Boolean = {
    val cur = Calendar.getInstance()
    cur.after(start) && cur.before(end)
  }

  def setDifficulty(worldNameList: List[String], difficulty: Difficulty): Unit = {
    worldNameList.foreach { name =>
      val world = Bukkit.getWorld(name)

      if (world == null)
        Bukkit.getLogger.warning(name + "という名前のワールドは存在しません。")
      else
        world.setDifficulty(difficulty)
    }
  }

  /**
   * 指定した名前のマインスタックオブジェクトを返す
   */
  // TODO これはここにあるべきではない
  @Deprecated()
  def findMineStackObjectByName(name: String): Option[MineStackObj] = {
    MineStackObjectList.minestacklist.find(_.mineStackObjName == name)
  }

  def isEnemy(entityType: EntityType): Boolean = Set(
    BLAZE,
    CAVE_SPIDER,
    CREEPER,
    ELDER_GUARDIAN,
    ENDERMAN,
    ENDERMITE,
    EVOKER,
    GHAST,
    GUARDIAN,
    HUSK,
    MAGMA_CUBE,
    PIG_ZOMBIE,
    SHULKER,
    SILVERFISH,
    SKELETON,
    SLIME,
    SPIDER,
    STRAY,
    VEX,
    VINDICATOR,
    WITCH,
    WITHER_SKELETON,
    ZOMBIE,
    ZOMBIE_VILLAGER
  ).contains(entityType)

  def isMineHeadItem(itemstack: ItemStack): Boolean = {
    itemstack.getType == Material.CARROT_STICK &&
      loreIndexOf(itemstack.getItemMeta.getLore.asScala.toList, "頭を狩り取る形をしている...") >= 0
  }

  def getSkullDataFromBlock(block: Block): Option[ItemStack] = {
    if (block.getType != Material.SKULL) return None

    val skull = block.getState.asInstanceOf[Skull]
    val itemStack = new ItemStack(Material.SKULL_ITEM)

    //SkullTypeがプレイヤー以外の場合，SkullTypeだけ設定して終わり
    if (skull.getSkullType != SkullType.PLAYER) {
      val durability = skull.getSkullType match {
        case SkullType.CREEPER => SkullType.CREEPER.ordinal.toShort
        case SkullType.DRAGON => SkullType.DRAGON.ordinal.toShort
        case SkullType.SKELETON => SkullType.SKELETON.ordinal.toShort
        case SkullType.WITHER => SkullType.WITHER.ordinal.toShort
        case SkullType.ZOMBIE => SkullType.ZOMBIE.ordinal.toShort
        case _ => itemStack.getDurability
      }
      return Some(itemStack.tap(_.setDurability(durability)))
    }
    //プレイヤーの頭の場合，ドロップアイテムからItemStackを取得．データ値をPLAYERにして返す
    Some(block.getDrops.asScala.head.tap(_.setDurability(SkullType.PLAYER.ordinal.toShort)))
  }

  /**
   * 指定された`String`が指定された[[ItemStack]]のloreに含まれているかどうか
   *
   * @param itemStack 確認する`ItemStack`
   * @param sentence  探す文字列
   * @return 含まれていれば`true`、含まれていなければ`false`。ただし、`ItemStack`に`ItemMeta`と`Lore`のいずれかがなければfalse
   */
  def isContainedInLore(itemStack: ItemStack, sentence: String): Boolean =
    if (!itemStack.hasItemMeta || !itemStack.getItemMeta.hasLore) false
    else loreIndexOf(itemStack.getItemMeta.getLore.asScala.toList, sentence) >= 0

  /**
   * loreを捜査して、要素の中に`find`が含まれているかを調べる。
   *
   * @param lore 探される対象
   * @param find 探す文字列
   * @return 見つかった場合はその添字、見つからなかった場合は-1
   */
  def loreIndexOf(lore: List[String], find: String): Int = {
    IntStream.range(0, lore.size)
      .filter { i => lore(i).contains(find) }
      .findFirst()
      .orElse(-1)
  }

  /**
   * PlayerDataでチャンク数をゲット・セットするためのenum
   */
  sealed trait DirectionType extends EnumEntry

  /**
   * PlayerDataなどで使用する方角関係のenum
   */
  sealed trait Direction extends EnumEntry

  case object DirectionType extends Enum[DirectionType] {

    val values: IndexedSeq[DirectionType] = findValues

    /**
     * for Java interop
     */
    def ahead: AHEAD.type = AHEAD

    def behind: BEHIND.type = BEHIND

    def right: RIGHT.type = RIGHT

    def left: LEFT.type = LEFT

    case object AHEAD extends DirectionType

    case object BEHIND extends DirectionType

    case object RIGHT extends DirectionType

    case object LEFT extends DirectionType
  }

  case object Direction extends Enum[Direction] {

    val values: IndexedSeq[Direction] = findValues

    case object NORTH extends Direction

    case object SOUTH extends Direction

    case object EAST extends Direction

    case object WEST extends Direction
  }

  /**
   * 死亡したエンティティの死因が棘の鎧かどうか
   */
  def isEntityKilledByThornsEnchant(entity: LivingEntity): Boolean = {
    if (entity == null) return false
    val event = entity.getLastDamageCause
    if (event == null) return false

    event.getCause == DamageCause.THORNS
  }
}
