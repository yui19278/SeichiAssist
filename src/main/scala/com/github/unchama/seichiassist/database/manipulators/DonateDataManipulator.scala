package com.github.unchama.seichiassist.database.manipulators

import java.sql.SQLException

import com.github.unchama.seichiassist.data.player.PlayerData
import com.github.unchama.seichiassist.database.{DatabaseConstants, DatabaseGateway}
import com.github.unchama.seichiassist.seichiskill.effect.ActiveSkillPremiumEffect
import com.github.unchama.util.ActionStatus
import org.bukkit.ChatColor._
import org.bukkit.Material
import org.bukkit.inventory.{Inventory, ItemStack}

class DonateDataManipulator(private val gateway: DatabaseGateway) {

  import com.github.unchama.util.syntax.ResultSetSyntax._

  import scala.jdk.CollectionConverters._

  def addPremiumEffectBuy(playerdata: PlayerData,
                          effect: ActiveSkillPremiumEffect): ActionStatus = {
    val command = ("insert into " + tableReference
      + " (playername,playeruuid,effectname,usepoint,date) "
      + "value("
      + "'" + playerdata.lowercaseName + "',"
      + "'" + playerdata.uuid.toString + "',"
      + "'" + effect.entryName + "',"
      + effect.usePoint + ","
      + "cast( now() as datetime )"
      + ")")

    gateway.executeUpdate(command)
  }

  def addDonate(name: String, point: Int): ActionStatus = {
    val command = ("insert into " + tableReference
      + " (playername,getpoint,date) "
      + "value("
      + "'" + name + "',"
      + point + ","
      + "cast( now() as datetime )"
      + ")")
    gateway.executeUpdate(command)
  }

  private def tableReference: String = gateway.databaseName + "." + DatabaseConstants.DONATEDATA_TABLENAME

  def loadDonateData(playerdata: PlayerData, inventory: Inventory): Boolean = {
    // TODO: ほんとうにStarSelectじゃなきゃだめ?
    val command = "select * from " + tableReference + " where playername = '" + playerdata.lowercaseName + "'"
    try {
      var count = 0
      gateway.executeQuery(command).recordIteration { lrs =>
        //ポイント購入の処理
        val getPoint = lrs.getInt("getpoint")
        val usePoint = lrs.getInt("usepoint")
        if (getPoint > 0) {
          val itemStack = new ItemStack(Material.DIAMOND)
          val lore = List(
            s"${RESET.toString}${GREEN}金額：${getPoint * 100}",
            s"$RESET${GREEN}プレミアムエフェクトポイント：+$getPoint",
            s"$RESET${GREEN}日時：${lrs.getString("date")}"
          )
          itemStack.setItemMeta {
            val meta = itemStack.getItemMeta
            meta.setDisplayName(s"$AQUA$UNDERLINE${BOLD}寄付")
            meta.setLore(lore.asJava)
            meta
          }
          inventory.setItem(count, itemStack)
        } else if (usePoint > 0) {
          val effect = ActiveSkillPremiumEffect.withName(lrs.getString("effectname"))
          val itemStack = new ItemStack(effect.material)

          val lore = List(
            s"$RESET${GOLD}プレミアムエフェクトポイント： -$usePoint",
            s"$RESET${GOLD}日時：${lrs.getString("date")}"
          )
          itemStack.setItemMeta {
            val meta = itemStack.getItemMeta
            meta.setDisplayName(s"$RESET${YELLOW}購入エフェクト：${effect.nameOnUI}")
            meta.setLore(lore.asJava)
            meta
          }
          inventory.setItem(count, itemStack)
        }
        count += 1
      }
    } catch {
      case e: SQLException =>
        println("sqlクエリの実行に失敗しました。以下にエラーを表示します")
        e.printStackTrace()
        return false
    }

    true
  }
}
