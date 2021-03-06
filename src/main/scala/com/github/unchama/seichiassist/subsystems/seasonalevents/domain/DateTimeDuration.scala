package com.github.unchama.seichiassist.subsystems.seasonalevents.domain

import java.time.{LocalDate, LocalDateTime, LocalTime}

case class DateTimeDuration(from: LocalDateTime, to: LocalDateTime) {
  require(from.isBefore(to) || from.isEqual(to), "期間の開始日が終了日よりも後に指定されています。")

  def isInDuration(base: LocalDateTime): Boolean = {
    val isAfterFrom = base.isEqual(from) || base.isAfter(from)
    val isBeforeTo = base.isEqual(to) || base.isBefore(to)

    isAfterFrom && isBeforeTo
  }
}

object DateTimeDuration {
  private val REBOOT_TIME = LocalTime.of(4, 10)

  def fromLocalDate(from: LocalDate, to: LocalDate): DateTimeDuration =
    DateTimeDuration(LocalDateTime.of(from, REBOOT_TIME), LocalDateTime.of(to, REBOOT_TIME))
}
