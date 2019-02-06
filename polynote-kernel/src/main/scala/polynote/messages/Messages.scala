package polynote.messages

import cats.MonadError
import cats.syntax.either._
import io.circe.{Decoder, Encoder}
import polynote.kernel._
import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs._
import scodec.codecs.implicits._
import io.circe.generic.semiauto._
import polynote.config.{DependencyConfigs, RepositoryConfig}
import polynote.data.Rope

import scala.collection.immutable.Queue

sealed trait Message

object Message {
  implicit val discriminated: Discriminated[Message, Byte] = Discriminated(byte)

  val codec: Codec[Message] = Codec[Message]

  def decode[F[_]](bytes: Array[Byte])(implicit F: MonadError[F, Throwable]): F[Message] = F.fromEither {
    codec.decode(BitVector(bytes)).toEither
      .map(_.value)
      .leftMap {
        err => new Exception(err.messageWithContext)
      }
  }

  def encode[F[_]](msg: Message)(implicit F: MonadError[F, Throwable]): F[BitVector] = F.fromEither {
    codec.encode(msg).toEither.map(_.value).leftMap {
      err => new Exception(err.messageWithContext)
    }
  }
}

abstract class MessageCompanion[T](msgId: Byte) {
  implicit final val discriminator: Discriminator[Message, T, Byte] = Discriminator(msgId)
}

final case class Error(code: Int, error: Throwable) extends Message

object Error extends MessageCompanion[Error](0) {
  implicit val codec: Codec[Error] = (uint16 ~ RuntimeError.throwableWithCausesCodec).xmap(
    t => Error(t._1, t._2),
    e => (e.code, e.error)
  )
}

final case class LoadNotebook(path: ShortString) extends Message
object LoadNotebook extends MessageCompanion[LoadNotebook](1)

final case class CellMetadata(
  disableRun: Boolean = false,
  hideSource: Boolean = false,
  hideOutput: Boolean = false
)

final case class NotebookCell(
  id: TinyString,
  language: TinyString,
  content: Rope,
  results: ShortList[Result] = ShortList(Nil),
  metadata: CellMetadata = CellMetadata()
) {
  def updateContent(fn: Rope => Rope): NotebookCell = copy(content = fn(content))
}

object NotebookCell {
  def apply(id: TinyString, language: TinyString, content: String): NotebookCell = NotebookCell(id, language, Rope(content))
}

final case class NotebookConfig(
  dependencies: Option[DependencyConfigs],
  repositories: Option[TinyList[RepositoryConfig]]
)

object NotebookConfig {
  implicit val encoder: Encoder[NotebookConfig] = deriveEncoder[NotebookConfig]
  implicit val decoder: Decoder[NotebookConfig] = deriveDecoder[NotebookConfig]
}

final case class Notebook(path: ShortString, cells: ShortList[NotebookCell], config: Option[NotebookConfig]) extends Message {
  def map(fn: NotebookCell => NotebookCell): Notebook = copy(
    cells = ShortList(cells.map(fn))
  )

  def updateCell(id: String)(fn: NotebookCell => NotebookCell): Notebook = map {
    case cell if cell.id == id => fn(cell)
    case cell => cell
  }

  def editCell(id: String, edits: List[ContentEdit]): Notebook = updateCell(id) {
    cell => cell.updateContent(_.withEdits(edits))
  }

  def addCell(cell: NotebookCell): Notebook = copy(cells = ShortList(cells :+ cell))

  def insertCell(cell: NotebookCell, after: Option[String]): Notebook = {
    val insertIndex = after.fold(0)(id => cells.indexWhere(_.id == id)) match {
      case -1 => 0
      case n => n
    }

    copy(
      cells = ShortList(
        cells.take(insertIndex + 1) ++ (cell :: cells.drop(insertIndex + 1))))
  }

  def deleteCell(id: String): Notebook = copy(cells = ShortList(cells.collect {
    case cell if cell.id != id => cell
  }))

  def setResults(id: String, results: List[Result]): Notebook = updateCell(TinyString(id)) {
    cell => cell.copy(results = ShortList(results))
  }
}
object Notebook extends MessageCompanion[Notebook](2)

final case class RunCell(notebook: ShortString, id: ShortList[TinyString]) extends Message
object RunCell extends MessageCompanion[RunCell](3)

final case class CellResult(notebook: ShortString, id: TinyString, result: Result) extends Message
object CellResult extends MessageCompanion[CellResult](4)


/**
  * A ContentEdit at a position deletes some amount (possibly nothing) after that position, and then inserts some
  * content (possibly empty) at that position.
  *
  * @param pos          The position at which the edit occurs
  * @param deleteLength The number of characters to delete after the given position
  * @param content      The content to insert
  */
final case class ContentEdit(pos: Int, deleteLength: Int, content: String) {
  def applyTo(rope: Rope): Rope = if (deleteLength > 0) rope.delete(pos, deleteLength).insertAt(pos, Rope(content)) else rope.insertAt(pos, Rope(content))

  // Given another edit which occured after this edit, create an edit that will be equivalent to applying this edit
  // when applied after the given edit instead.
  def rebase(other: ContentEdit): ContentEdit = other match {
    // if the other edit is entirely before this edit, just shift this edit by the length delta
    case ContentEdit(otherPos, otherDeleteLength, otherContent) if otherPos + otherDeleteLength <= pos =>
      copy(pos = pos + otherContent.length - otherDeleteLength)

    // if the other edit is entirely after this edit, nothing to do
    case ContentEdit(otherPos, _, _) if otherPos >= pos + deleteLength => this

    // if this edit's deletion is completely inside that edit's deletion, then just insert my content after theirs
    case ContentEdit(otherPos, otherDeleteLength, otherContent) if otherPos <= pos && otherPos + otherDeleteLength >= pos + deleteLength =>
      ContentEdit(otherPos + otherContent.length, 0, content)

    // If that edit's deletion is completely inside this edit's deletion, we'll subsume their edit into ours
    // TODO: This one would be better if it could be split into two edits, but that complicates the API. What if rebase were on UpdateCell instead?
    case ContentEdit(otherPos, otherDeleteLength, otherContent) if otherPos >= pos && otherPos + otherDeleteLength < pos + deleteLength =>
      val (before, after) = content.splitAt(otherPos - pos)
      ContentEdit(pos, deleteLength + (otherContent.length - otherDeleteLength), before + otherContent + after)

    // Overlaps with other edit, with theirs starting before mine – they already deleted part of what I was going to
    // Track the point until which I was going to delete, and delete from the end of their content up to that point
    // And insert my content after theirs.
    case ContentEdit(otherPos, otherDeleteLength, otherContent) if otherPos <= pos =>

      // they already deleted this much of what I was going to delete
      val alreadyDeleted = math.max(0, otherPos + otherDeleteLength - pos)

      ContentEdit(otherPos + otherContent.length, deleteLength - alreadyDeleted, content)

    // Overlaps with other edit, with mine starting before theirs. Delete up to their insertion point, and then
    // insert my content before theirs.
    case ContentEdit(otherPos, otherDeleteLength, otherContent) if otherPos > pos =>
      ContentEdit(pos, otherPos - pos, content)
  }
}



sealed trait NotebookUpdate extends Message {
  def globalVersion: Int
  def localVersion: Int
  def notebook: ShortString

  def withVersions(global: Int, local: Int): NotebookUpdate = this match {
    case u @ UpdateCell(_, _, _, _, _) => u.copy(globalVersion = global, localVersion = local)
    case i @ InsertCell(_, _, _, _, _) => i.copy(globalVersion = global, localVersion = local)
    case d @ DeleteCell(_, _, _, _)    => d.copy(globalVersion = global, localVersion = local)
    case u @ UpdateConfig(_, _, _, _)  => u.copy(globalVersion = global, localVersion = local)
    case l @ SetCellLanguage(_, _, _, _, _) => l.copy(globalVersion = global, localVersion = local)
  }

  // transform this update so that it has the same effect when applied after the given update
  def rebase(prev: NotebookUpdate): NotebookUpdate = (this, prev) match {
    case (i@InsertCell(_, _, _, cell1, after1), InsertCell(_, _, _, cell2, after2)) if after1 == after2 =>
      // we both tried to insert a cell after the same cell. Transform the first update so it inserts after the cell created by the second update.
      i.copy(after = Some(cell2.id))

    case (u@UpdateCell(_, _, _, id1, edits1), UpdateCell(_, _, _, id2, edits2)) if id1 == id2 =>
      // we both tried to edit the same cell. Transform first edits so they apply to the document state as it exists after the second edits are already applied.
      val rebasedEdits1 = edits1.map {
        edit => edits2.foldLeft(edit)((e1, e2) => e1.rebase(e2))
      }
      u.copy(edits = ShortList(rebasedEdits1))

    // all other cases should be independent (TODO: they're not yet, though)
    case _ => this

  }
}

object NotebookUpdate {
  def unapply(message: Message): Option[NotebookUpdate] = message match {
    case msg: NotebookUpdate => Some(msg)
    case _ => None
  }
}

final case class UpdateCell(notebook: ShortString, globalVersion: Int, localVersion: Int, id: TinyString, edits: ShortList[ContentEdit]) extends Message with NotebookUpdate
object UpdateCell extends MessageCompanion[UpdateCell](5)

final case class InsertCell(notebook: ShortString, globalVersion: Int, localVersion: Int, cell: NotebookCell, after: Option[TinyString]) extends Message with NotebookUpdate
object InsertCell extends MessageCompanion[InsertCell](6)

final case class CompletionsAt(notebook: ShortString, id: TinyString, pos: Int, completions: ShortList[Completion]) extends Message
object CompletionsAt extends MessageCompanion[CompletionsAt](7)

final case class ParametersAt(notebook: ShortString, id: TinyString, pos: Int, signatures: Option[Signatures]) extends Message
object ParametersAt extends MessageCompanion[ParametersAt](8)

final case class KernelStatus(notebook: ShortString, update: KernelStatusUpdate) extends Message
object KernelStatus extends MessageCompanion[KernelStatus](9)

final case class UpdateConfig(notebook: ShortString, globalVersion: Int, localVersion: Int, config: NotebookConfig) extends Message with NotebookUpdate
object UpdateConfig extends MessageCompanion[UpdateConfig](10)

final case class SetCellLanguage(notebook: ShortString, globalVersion: Int, localVersion: Int, id: TinyString, language: TinyString) extends Message with NotebookUpdate
object SetCellLanguage extends MessageCompanion[SetCellLanguage](11)

final case class StartKernel(notebook: ShortString, level: Byte) extends Message
object StartKernel extends MessageCompanion[StartKernel](12) {
  // TODO: should probably make this an enum that codecs to a byte, but don't want to futz with that right now
  final val NoRestart = 0.toByte
  final val WarmRestart = 1.toByte
  final val ColdRestart = 2.toByte
  final val Kill = 3.toByte
}

final case class ListNotebooks(paths: List[ShortString]) extends Message
object ListNotebooks extends MessageCompanion[ListNotebooks](13)

final case class CreateNotebook(path: ShortString) extends Message
object CreateNotebook extends MessageCompanion[CreateNotebook](14)

final case class DeleteCell(notebook: ShortString, globalVersion: Int, localVersion: Int, id: TinyString) extends Message with NotebookUpdate
object DeleteCell extends MessageCompanion[DeleteCell](15)