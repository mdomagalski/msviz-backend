package ch.isbsib.proteomics.mzviz.matches.models

import ch.isbsib.proteomics.mzviz.theoretical.{SequenceSource, AccessionCode, ProteinIdentifier}

/**
 * @author Roman Mylonas, Trinidad Martin & Alexandre Masselot
 * copyright 2014-2015, SIB Swiss Institute of Bioinformatics
 */
case class ProteinRef(AC: AccessionCode, identifiers: Set[ProteinIdentifier] = Set(), source: Option[SequenceSource])
