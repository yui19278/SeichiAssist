package com.github.unchama.targetedeffect.player

import com.github.unchama.targetedeffect.TargetedEffect.TargetedEffect
import com.github.unchama.targetedeffect.TargetedEffects
import org.bukkit.Sound
import org.bukkit.entity.Player

/**
 * プレーヤーの位置で音を鳴らすような[TargetedEffect].
 */
object FocusedSoundEffect {
  def apply(sound: Sound, volume: Float, pitch: Float): TargetedEffect[Player] =
    TargetedEffects.delay { player =>
      player.playSound(player.getLocation, sound, volume, pitch)
    }
}
