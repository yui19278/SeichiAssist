package com.github.unchama.seichiassist.commands

import com.github.unchama.contextualexecutor.asNonBlockingTabExecutor
import com.github.unchama.messaging.EmptyMessage
import com.github.unchama.messaging.asResponseToSender
import com.github.unchama.messaging.plus
import com.github.unchama.contextualexecutor.executors.BranchedExecutor
import com.github.unchama.seichiassist.SeichiAssist
import com.github.unchama.seichiassist.commands.contextual.builder.BuilderTemplates.playerCommandBuilder

object EffectCommand {

  private val toggleExecutor = playerCommandBuilder
      .execution { context ->
        val playerData = SeichiAssist.playermap[context.sender.uniqueId] ?: return@execution EmptyMessage
        val toggleResponse = playerData.fastDiggingEffectSuppressor.toggleEffect()
        val guidance = "再度 /ef コマンドを実行することでトグルします。".asResponseToSender()

        toggleResponse + guidance
      }
      .build()

  private val messageFlagToggleExecutor = playerCommandBuilder
      .execution { context ->
        val playerData = SeichiAssist.playermap[context.sender.uniqueId] ?: return@execution EmptyMessage

        playerData.toggleMessageFlag()
      }
      .build()

  val executor = BranchedExecutor(
      mapOf("smart" to messageFlagToggleExecutor),
      whenArgInsufficient = toggleExecutor
  ).asNonBlockingTabExecutor()

}
