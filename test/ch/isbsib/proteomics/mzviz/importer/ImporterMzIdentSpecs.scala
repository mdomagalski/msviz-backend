package ch.isbsib.proteomics.mzviz.importer

import org.specs2.mutable._

/**
 *  @author Alexandre Masselot
 */
class ImporterMzIdentSpecs extends Specification {
    "read" should {
      "get something" in {
        ImporterMzIdent.parse("test/resources/F001644.mzid") must equalTo(42)
      }
    }
}