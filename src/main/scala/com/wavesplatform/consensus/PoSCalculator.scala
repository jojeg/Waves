package com.wavesplatform.consensus

import com.wavesplatform.crypto
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.features.FeatureProvider._
import com.wavesplatform.state.Blockchain

trait PoSCalculator {
  protected val HitSize: Int              = 8
  protected val MeanCalculationDepth: Int = 3

  // Min BaseTarget value is 9 because only in this case it is possible to get to next integer value (10)
  // then increasing base target by 11% and casting it to Long afterward (see lines 55 and 59)
  private val MinBaseTarget: Long = 9

  private val MinBlockDelaySeconds = 53
  private val MaxBlockDelaySeconds = 67
  private val BaseTargetGamma      = 64

  def generatorSignature(signature: Array[Byte], publicKey: Array[Byte]): Array[Byte] = {
    val s = new Array[Byte](crypto.DigestSize * 2)
    System.arraycopy(signature, 0, s, 0, crypto.DigestSize)
    System.arraycopy(publicKey, 0, s, crypto.DigestSize, crypto.DigestSize)
    crypto.fastHash(s)
  }

  def hit(generatorSignature: Array[Byte]): BigInt = BigInt(1, generatorSignature.take(HitSize).reverse)

  def baseTarget(targetBlockDelaySeconds: Long,
                 prevHeight: Int,
                 prevBaseTarget: Long,
                 parentTimestamp: Long,
                 maybeGreatGrandParentTimestamp: Option[Long],
                 timestamp: Long): Long = {

    if (prevHeight % 2 == 0) {
      val meanBlockDelay  = maybeGreatGrandParentTimestamp.fold(timestamp - parentTimestamp)(ts => (timestamp - ts) / MeanCalculationDepth) / 1000
      val minBlockDelay   = normalize(MinBlockDelaySeconds, targetBlockDelaySeconds)
      val maxBlockDelay   = normalize(MaxBlockDelaySeconds, targetBlockDelaySeconds)
      val baseTargetGamma = normalize(BaseTargetGamma, targetBlockDelaySeconds)

      val baseTarget = (if (meanBlockDelay > targetBlockDelaySeconds) {
                          prevBaseTarget * Math.min(meanBlockDelay, maxBlockDelay) / targetBlockDelaySeconds
                        } else {
                          prevBaseTarget - prevBaseTarget * baseTargetGamma *
                            (targetBlockDelaySeconds - Math.max(meanBlockDelay, minBlockDelay)) / (targetBlockDelaySeconds * 100)
                        }).toLong

      normalizeBaseTarget(baseTarget, targetBlockDelaySeconds)
    } else {
      prevBaseTarget
    }

  }

  def target(prevBlockTimestamp: Long, prevBaseTarget: Long, timestamp: Long, balance: Long): BigInt = {
    val blockDelaySeconds = (timestamp - prevBlockTimestamp) / 1000
    BigInt(prevBaseTarget) * blockDelaySeconds * balance
  }

  def time(hit: BigInt, bt: Long, balance: Long): Long = ((hit * 1000) / (BigInt(bt) * balance)).toLong

  private def normalize(value: Long, targetBlockDelaySeconds: Long): Double =
    value * targetBlockDelaySeconds / (60: Double)

  private def normalizeBaseTarget(baseTarget: Long, targetBlockDelaySeconds: Long): Long = {
    val maxBaseTarget = Long.MaxValue / targetBlockDelaySeconds
    if (baseTarget < MinBaseTarget) MinBaseTarget else if (baseTarget > maxBaseTarget) maxBaseTarget else baseTarget
  }

}

class PoSSelector(val blockchain: Blockchain) extends PoSCalculator {
  override def hit(generatorSignature: Array[Byte]): BigInt =
    if (fair(blockchain.height)) FairPoSCalculator.hit(generatorSignature) else NxtPoSCalculator.hit(generatorSignature)

  override def time(hit: BigInt, bt: Long, balance: Long): Long =
    if (fair(blockchain.height)) FairPoSCalculator.time(hit, bt, balance) else NxtPoSCalculator.time(hit, bt, balance)

  private def fair(height: Int): Boolean = blockchain.activatedFeaturesAt(height).contains(BlockchainFeatures.FairPoS.id)
}

object NxtPoSCalculator extends PoSCalculator

object FairPoSCalculator extends PoSCalculator {
  private val MaxSignature: Array[Byte] = Array.fill[Byte](HitSize)(-1)
  private val MaxHit: BigDecimal        = BigDecimal(BigInt(1, MaxSignature))
  private val C1                        = 120
  private val C2                        = 1000
  private val TMin                      = 5000

  override def time(hit: BigInt, bt: Long, balance: Long): Long = {
    val h = BigDecimal(hit)
    round(TMin + C1 * math.log(1 - C2 * math.log((h / MaxHit).toDouble) / (bt * balance).toDouble)).toLong
  }

  private def round(x: BigDecimal): BigInt = x.setScale(0, BigDecimal.RoundingMode.CEILING).toBigInt()
}
