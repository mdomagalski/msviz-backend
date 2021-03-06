package ch.isbsib.proteomics.mzviz.experimental.models

import ch.isbsib.proteomics.mzviz.experimental._
import ch.isbsib.proteomics.mzviz.commons._
import org.specs2.mutable.Specification

/**
 * @author Roman Mylonas, Trinidad Martin & Alexandre Masselot
 * copyright 2014-2015, SIB Swiss Institute of Bioinformatics
 */
class SpectrumIdSpecs extends Specification {
  "case" should {
    "need no new" in {
      val spid = SpectrumRef(ScanNumber(12),
        ExpPeakPrecursor(moz = Moz(123.45), intensity=Intensity(678), retentionTime=RetentionTime(123.45), charge=Charge(2)), "no title", SpectrumId(SpectrumUniqueId("no title"), RunId("Paf")))
      spid.scanNumber must equalTo(ScanNumber(12))
    }
  }
}
