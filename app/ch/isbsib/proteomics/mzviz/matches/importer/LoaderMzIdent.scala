package ch.isbsib.proteomics.mzviz.matches.importer

import java.io.{File, FileInputStream, InputStream}

import ch.isbsib.proteomics.mzviz.commons.helpers.OptionConverter
import ch.isbsib.proteomics.mzviz.experimental.{SpectrumUniqueId, RunId}
import ch.isbsib.proteomics.mzviz.experimental.models.SpectrumId
import ch.isbsib.proteomics.mzviz.matches.SearchId
import ch.isbsib.proteomics.mzviz.matches.models._
import ch.isbsib.proteomics.mzviz.modifications.{ModifName}
import ch.isbsib.proteomics.mzviz.modifications.models.{PositionedModifRef}
import ch.isbsib.proteomics.mzviz.theoretical.models.SearchDatabase
import ch.isbsib.proteomics.mzviz.theoretical.{AccessionCode, NumDatabaseSequences, SequenceSource}
import org.apache.commons.io.FilenameUtils
import org.expasy.mzjava.proteomics.io.ms.ident.{MzIdentMlReader, PSMReaderCallback}
import org.expasy.mzjava.proteomics.mol.modification.ModAttachment
import org.expasy.mzjava.proteomics.mol.modification.unimod.UnimodManager
import org.expasy.mzjava.proteomics.ms.ident.{ModificationMatch, PeptideMatch, PeptideProteinMatch, SpectrumIdentifier}
import play.{Logger, Play}
import com.google.common.base.Optional

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
 * @author Roman Mylonas, Trinidad Martin & Alexandre Masselot
 * copyright 2014-2015, SIB Swiss Institute of Bioinformatics
 */
object LoaderMzIdent {

  /**
   * parse a .mzid file and return a full run.
   * @param file an .mzid file
   * @return
   */
  def parse(file: File, searchId: SearchId, runId: RunId): Seq[PepSpectraMatch] = {
    // set the unimodXmlPath for MzJavaunimodXmlPath
    //val s = Play.application().configuration().getString("unimod.xml")
    //println("unimod location: " + s)
    //UnimodManager.setUnimodPath(s)

    // data from MzJava parser are stored in a list
    val searchResults = mzJavaParse(file)

    // get the info about the SearchDatabases
    val searchDbSourceInfo = parseSearchDbSourceInfo(file)
    
    // convert the resulting list into our proper object
    searchResults.map({ t =>
      PepSpectraMatch(
        searchId = searchId,
        spectrumId = SpectrumId(
          SpectrumUniqueId(t._1.getSpectrum),
          runId = runId),
        pep = convertPeptide(t._2),
        matchInfo = convertPepMatch(t),
        proteinList = convertProtMatches(t._2, searchDbSourceInfo))
    }).toSeq

  }
  /**
   * parse a .mzid file and return search information.
   * @param file an .mzid file
   * @return
   */
  def parseSearchInfo(file: File, searchId: SearchId): Iterator[SearchInfo] = {

    // get the info about the SearchDatabases
    val title =parseTitleFilename(file)
    val database= parseSearchDbSourceInfo(file)
    val username=parseUsernameFilename(file)
    val searchI=SearchInfo(searchId,title,database,username)
    val it: Iterator[SearchInfo] = Iterator(searchI)
    it
    //it.map(searchI)
  }

  /**
   * parse the spectraFileName from the MzIdenML file. We do this seperately, since the MzJava parser doesn't take care of this information.
   * TODO: adapt MzJava MzIdentMlParser, so that it parses spectra filename information
   * @param filename MzIdentML path
   * @return spectra file name (e.g. blabla.mgf)
   */
  def parseSpectraFilename(filename: String): String = {
    val mzIdentML = scala.xml.XML.loadFile(filename)
    val spectraDataLocation = mzIdentML \\ "SpectraData" \ "@location"
    FilenameUtils.getBaseName(spectraDataLocation.text)
  }

  /**
   * parse the title from the MzIdenML file.
   * @param filename MzIdentML path
   * @return title file name
   */
  def parseTitleFilename(filename: File): String = {
    val mzIdentML = scala.xml.XML.loadFile(filename)
    // to have only the first of the values
    val titleLocation = (mzIdentML \\ "userParam" \\ "@value").head
    FilenameUtils.getBaseName(titleLocation.text)
  }

  /**
   * parse the username from the MzIdenML file.
   * @param filename MzIdentML path
   * @return username
   */
  def parseUsernameFilename(filename: File): String = {
    val mzIdentML = scala.xml.XML.loadFile(filename)
    val usernameLocation =mzIdentML \\ "Person" \\ "@name"
    FilenameUtils.getBaseName(usernameLocation.text)
  }

  /**
   * parse the database  from the MzIdenML file. We do this seperately, since the MzJava parser doesn't take care of this information.
   * TODO: adapt MzJava MzIdentMlParser, so that it parses searchDb information
   * @param file MzIdentML file
   * @return a list of Tuples containing the SequenceSource and the number of entries
   */
  def parseSearchDbSourceInfo(file: File):Seq[SearchDatabase] = {
    val mzIdentML = scala.xml.XML.loadFile(file)

    (mzIdentML \\ "SearchDatabase").map { db =>
      SearchDatabase((db \ "@id").text,((db \ "@version").text),((db \ "@numDatabaseSequences").text.toInt))
    }
  }


  /**
   * extract all the protein matches and convert it to our ProteinMatch class
   * @param mzJavaMatch a PeptideMatch obtained from the MzJava mzIdentML parser
   * @return
   */
  def convertPeptide(mzJavaMatch: PeptideMatch): Peptide = {
    val pep = mzJavaMatch.toPeptide()

    // since MzJava can throw an Exception on that, we'll wrap it around a try catch
    var mMass:Option[Double] = None
    try{
      mMass = Option(pep.getMolecularMass)
    }catch{
      case e: Exception => Logger.warn(e.getMessage)
    }

    Peptide(sequence = pep.toSymbolString, molMass = mMass, modificationNames = convertModificationList(mzJavaMatch))
  }


  /**
   * extract all the protein matches and convert it to our ProteinMatch class
   * @param mzJavaMatch a PeptideMatch obtained from the MzJava mzIdentML parser
   * @return
   */
  def convertProtMatches(mzJavaMatch: PeptideMatch, searchDbSourceInfo: Seq[SearchDatabase]): Seq[ProteinMatch] = {
    (for {
      pMatch: PeptideProteinMatch <- mzJavaMatch.getProteinMatches.iterator().asScala
    } yield {

      // match MzJava HitType to our own
      val isDecoy = mzJavaMatch.getProteinMatches.get(0).getHitType match {
        case PeptideProteinMatch.HitType.DECOY => Some(true)
        case PeptideProteinMatch.HitType.TARGET => Some(false)
        case _ => None
      }

      val searchDb = searchDbSourceInfo.find(db =>
        db.id==pMatch.getSearchDatabase.get()).map(db =>
          SequenceSource(db.version)
      )

      ProteinMatch(proteinRef = ProteinRef(AC = AccessionCode(pMatch.getAccession),
        source = searchDb),
        previousAA = OptionConverter.convertGoogleOption(pMatch.getPreviousAA),
        nextAA = OptionConverter.convertGoogleOption(pMatch.getNextAA),
        startPos = pMatch.getStart,
        endPos = pMatch.getEnd,
        isDecoy = isDecoy
      )
    }).toSeq
  }

  /**
   * convert a MzJava PeptideMatch into our PepMatchInfo object
   * @param mzJavaRes a Tuple of a SpectrumIdentifier and a PeptideMatch obtained from the MzJava mzIdentML parser
   * @return
   */
  def convertPepMatch(mzJavaRes: Tuple2[SpectrumIdentifier, PeptideMatch]): PepMatchInfo = {
    val mzJavaMatch = mzJavaRes._2


    // create the score map
    val scoreMap:Map[String, Double] =
      (for {k <- mzJavaMatch.getScoreMap.keys()}
      yield {
        val key = k.asInstanceOf[String]
        key -> mzJavaMatch.getScoreMap.get(key)
      }).toMap

    // create and return a new PepMatchInfo
    PepMatchInfo(scoreMap = scoreMap,
      numMissedCleavages = Option(mzJavaMatch.getNumMissedCleavages),
      massDiff = Option(mzJavaMatch.getNumMissedCleavages),
      rank = mzJavaMatch.getRank,
      chargeState = OptionConverter.convertGoogleOption[Int](mzJavaRes._1.getAssumedCharge.asInstanceOf[Optional[Int]]),
      totalNumIons = Option(mzJavaMatch.getTotalNumIons),
      isRejected = Option(mzJavaMatch.isRejected))

  }

  /**
   * convert modification list from MzJava to MsViz
   * @param pep an .mzid file
   * @return
   */
  def convertModificationList(pep: PeptideMatch): Vector[Seq[ModifName]] = {

    // get all modifications from MzJava
    val modifsAll = pep.getModifications(ModAttachment.all)

    // create list of positioned modifications
    val modifs:Seq[PositionedModifRef] = modifsAll.asScala.flatMap(convertMzModif(_))

    // create a vector with a list of modifs for each position (+2 because we add N and C-term modifications)
    modifs.foldLeft(Vector.fill[Seq[ModifName]](pep.toSymbolString.length + 2)(Nil))({
      (acc: Vector[Seq[ModifName]], posMods) => acc.updated(posMods.pos, acc(posMods.pos):+posMods.modifName)
    })

  }

  /**
   * convert a MzJava modification to our Modification
   * @param modif a MzJava ModificationMatch
   * @return a Modification
   */
  def convertMzModif(modif: ModificationMatch): Seq[PositionedModifRef] = {

    // adapt the position
    val pos:Int = modif.getModAttachment.name match {
      case "N_TERM" => 0
      case "C_TERM" => modif.getPosition + 2
      case _ => modif.getPosition + 1
    }

    // get the postioned modifications
    val candidates:Seq[PositionedModifRef] = (0 to modif.getCandidateCount-1).toList.map({ i =>
      // @TODO source should be parsed from MzIdentML (adaptations in MzJava needed). Currently the source is UNIMOD obtained from Mascot server
      PositionedModifRef(modifName = ModifName(modif.getModificationCandidate(i).getLabel), pos = pos)
    })

    candidates

  }


    /**
   * parse .mzid file using MzIdentMlReader from MzJava
   * @param file an .mzid file
   * @return
   */
  def mzJavaParse(file: File): ListBuffer[Tuple2[SpectrumIdentifier, PeptideMatch]] = {
    val searchResults = ListBuffer[Tuple2[SpectrumIdentifier, PeptideMatch]]()
    val insertIdResultCB: PSMReaderCallback = new PSMReaderCallback {
      def resultRead(identifier: SpectrumIdentifier, peptideMatch: PeptideMatch) = searchResults.append(Tuple2(identifier, peptideMatch))
    }

    val fr: InputStream = new FileInputStream(file)
    val reader: MzIdentMlReader = new MzIdentMlReader()

    reader.parse(fr, insertIdResultCB)
    searchResults
  }


}
