package com.github.unchama.seichiassist.commands.contextual

import arrow.effects.IO
import arrow.effects.extensions.io.fx.fx
import arrow.effects.extensions.io.unsafeRun.runBlocking
import arrow.unsafe
import com.github.unchama.util.ActionStatus.Fail
import com.github.unchama.util.ActionStatus.Ok
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

/**
 * コマンド実行時に[TabExecutor]へ渡される情報をラップした[RawCommandContext]を用いて処理を行うオブジェクトへのinterface.
 */
interface ContextualExecutor {

    /**
     * [rawContext] に基づいて, コマンドが行うべき処理を表す[IO]を計算する.
     */
    fun executionFor(rawContext: RawCommandContext): IO<Unit>

    /**
     * [context] に基づいてTab補完の候補をListで返却する.
     */
    fun tabCandidatesFor(context: RawCommandContext): List<String>? = null

}

/**
 * この[ContextualExecutor]を[TabExecutor]オブジェクトへ変換する.
 *
 * この関数から得られる[TabExecutor]は[ContextualExecutor.executionFor]を非同期スレッドから発火するため,
 * 同期的な実行を期待する場合には[ContextualExecutor.executionFor]側で実行するコンテキストを指定せよ.
 */
fun ContextualExecutor.asTabExecutor(): TabExecutor {
    return object: TabExecutor {
        override fun onCommand(sender: CommandSender, command: Command, alias: String, args: Array<out String>): Boolean {
            val context = RawCommandContext(sender, ExecutedCommand(command, alias), args.toList())
            val program =
                    fx {
                        continueOn(NonBlocking)
                        !effect {
                            executionFor(context)
                        }
                    }

            unsafe { runBlocking { program } }

            // 非同期の操作を含むことを前提とするため, Bukkitへのコマンドの成否を必ず成功扱いにする
            return true
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
            val context = RawCommandContext(sender, ExecutedCommand(command, alias), args.toList())

            return tabCandidatesFor(context)
        }
    }
}

