package com.github.unchama.seichiassist.subsystems.anywhereender.bukkit.command

import cats.arrow.FunctionK
import cats.effect.{Effect, IO}
import cats.effect.implicits._
import com.github.unchama.seichiassist.commands.contextual.builder.BuilderTemplates.playerCommandBuilder
import com.github.unchama.seichiassist.subsystems.anywhereender.AnywhereEnderChestAPI
import org.bukkit.command.TabExecutor

/**
 * エンダーチェストを開くコマンド
 */
object EnderChestCommand {
  def executor[F[_]: Effect](implicit enderChestAccessApi: AnywhereEnderChestAPI[F]): TabExecutor =
    playerCommandBuilder
      .argumentsParsers(List())
      .withEffectAsExecution {
        enderChestAccessApi.openEnderChestOrNotifyInsufficientLevel.mapK(Effect.toIOK)
      }
      .build()
      .asNonBlockingTabExecutor()
}
