package eu.ww86.hoconfmt

import org.ekrich.config.impl.*
import org.ekrich.config.parser.{ConfigDocumentFactory, ConfigNode}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** A key a later definition of the same path replaces, so the earlier value never takes effect. */
enum Finding derives CanEqual {

  /** The definition at `earlierLine` writes `keyPath`, and the one at `laterLine` replaces it. */
  case KeyDefinedAgain(keyPath: KeyPath, earlierLine: Int, laterLine: Int)

  /** The name the examples catalogue carries in its `findings` list, as refusals carry a kind. */
  def kind: String = this match {
    case Finding.KeyDefinedAgain(_, _, _) => "key-defined-again"
  }
}

/** Where a key stands in a text: fields by name, the objects inside an array by their position. */
final case class KeyPath(segments: List[KeyPath.Segment]) derives CanEqual {

  /** The path as a warning names it: `a.b`, `a[0].b`, and a name holding a dot quoted, since
    * `"a.b"` and `a.b` are different keys.
    */
  def rendered: String =
    segments.foldLeft("") { (text, segment) =>
      segment match {
        case KeyPath.Segment.Name(value) => text + (if (text.isEmpty) "" else ".") + KeyPath.quotedIfNeeded(value)
        case KeyPath.Segment.Index(at)   => s"$text[$at]"
      }
    }
}

object KeyPath {

  enum Segment derives CanEqual {
    case Name(value: String)
    case Index(position: Int)
  }

  /** A path of object fields, for the tests and callers that hold no array position. */
  def of(names: String*): KeyPath = KeyPath(names.toList.map(Segment.Name(_)))

  private def quotedIfNeeded(name: String): String =
    if (name.nonEmpty && !name.exists(c => c == '.' || c == '"' || c.isWhitespace)) name
    else {
      val escaped = name.flatMap {
        case '"'          => "\\\""
        case '\\'         => "\\\\"
        case '\n'         => "\\n"
        case '\r'         => "\\r"
        case '\t'         => "\\t"
        case c if c < ' ' => "\\u%04x".format(c.toInt)
        case c            => c.toString
      }
      s""""$escaped""""
    }
}

/** Reports the keys whose earlier definition a later one makes no difference.
  *
  * A later definition of a path replaces the earlier one unless both are objects, which merge; and
  * an include of a missing file is not read, so what an included file defines is out of scope. A
  * later definition holding a substitution is left alone: the merge stays unresolved, and what the
  * substitution does not supply the earlier value still does.
  *
  * The definitions are read from the syntax-preserving document parse of the text, not from the
  * parse a format makes: that one merges each path into a single value, which drops the replaced
  * definition and with it every line the report would need — `parseString("x = 1\nx = 2")` renders
  * `x = 2` alone. The document tree keeps both fields, and its nodes render in source order except
  * for the characters their `include` nodes lose, so the lines counted here are the text's own.
  */
object DuplicateReport {

  /** The definitions a later one replaces, in source order. */
  def findings(source: String): Either[Refusal, List[Finding]] =
    findings(source, None)

  /** As [[findings(String)]], with `origin` naming the text in a parse failure. */
  def findings(source: String, origin: String): Either[Refusal, List[Finding]] =
    findings(source, Some(origin))

  private def findings(source: String, origin: Option[String]): Either[Refusal, List[Finding]] =
    document(source, origin).map(tree => report(defined(tree)))

  private def document(source: String, origin: Option[String]): Either[Refusal, ConfigNodeRoot] = {
    val options = origin.fold(HoconFormatter.parseOptions)(HoconFormatter.parseOptions.setOriginDescription)
    try {
      ConfigDocumentFactory.parseString(source, options) match {
        case simple: SimpleConfigDocument => Right(simple.configNodeTree)
        // sconfig publishes no traversal API; callers must surface this failure if its
        // document implementation changes rather than treating it as an empty report.
        case other => Left(Refusal.NotHocon(s"no document tree (${other.getClass.getName})"))
      }
    } catch {
      case NonFatal(e) => Left(Refusal.NotHocon(Option(e.getMessage).getOrElse(e.toString)))
    }
  }

  /** One field of the text: where it stands, the line its key starts on, whether its value is an
    * object (two objects merge, so neither replaces the other), whether it holds a substitution,
    * and whether it writes nothing (an object with no value inside: the only work it does is
    * replace a value that is not an object).
    *
    * `position` counts the statements the walk has passed, so two definitions sharing a line can
    * still be told apart by which stands first. A line carries no column, and an include on the
    * same line as a definition is either before it or after it; only the position says which.
    */
  private[hoconfmt] case class Definition(
      keyPath: KeyPath,
      line: Int,
      position: Int,
      objectValued: Boolean,
      holdsSubstitution: Boolean,
      writesNothing: Boolean,
      arrayScope: List[Int]
  )

  /** One `include` directive: the line it stands on, its position among the statements, the object
    * it stands in, and the array scope of that object. Read from the masked text, where an include
    * is a placeholder field, so the walk finds it by that field's key.
    */
  private[hoconfmt] case class Include(line: Int, position: Int, scope: KeyPath, arrayScope: List[Int])

  /** Everything the walk finds in source order: the fields and the includes. */
  private[hoconfmt] case class Outline(definitions: Vector[Definition], includes: Vector[Include]) {

    def add(definition: Definition): Outline = copy(definitions = definitions :+ definition)

    def addAll(more: Vector[Definition]): Outline = copy(definitions = definitions ++ more)

    def add(include: Include): Outline = copy(includes = includes :+ include)
  }

  /** Every field and every include the document parse holds: what [[IncludeShadow]] reads to see
    * which definitions a formatting drops around an include.
    */
  private[hoconfmt] def outline(source: String): Either[Refusal, Outline] =
    document(source, None).map(outlineOf)

  private val emptyOutline = Outline(Vector.empty, Vector.empty)

  private def outlineOf(tree: ConfigNodeRoot): Outline =
    walk(tree, Nil, 1, emptyOutline, Nil)._2

  private def defined(tree: ConfigNodeRoot): Vector[Definition] =
    outlineOf(tree).definitions

  /** The definitions and includes `node` holds, and the line the text after it starts on. The line
    * advances by the newlines of every render in source order.
    */
  private def walk(
      node: ConfigNode,
      at: List[KeyPath.Segment],
      line: Int,
      found: Outline,
      arrayScope: List[Int]
  ): (Int, Outline) =
    node match {
      case field: ConfigNodeField => walkField(field, at, line, found, arrayScope)
      // The definition offset identifies each array with fields uniquely; empty arrays have no
      // descendants to compare. Nesting keeps even arrays starting at the same offset apart.
      case array: ConfigNodeArray =>
        walkItems(array.children.asScala.toList, at, line, found, 0, arrayScope :+ found.definitions.size)
      case complex: ConfigNodeComplexValue => walkAll(complex.children.asScala.toList, at, line, found, arrayScope)
      case _                               => (line + newlines(node.render), found)
    }

  private def walkAll(
      children: List[ConfigNode],
      at: List[KeyPath.Segment],
      line: Int,
      found: Outline,
      arrayScope: List[Int]
  ): (Int, Outline) =
    children.foldLeft((line, found)) { case ((current, acc), child) => walk(child, at, current, acc, arrayScope) }

  /** The objects in an array are reached by their position, which is part of where their fields
    * stand: the same key in two elements is not two definitions of one path.
    */
  private def walkItems(
      children: List[ConfigNode],
      at: List[KeyPath.Segment],
      line: Int,
      found: Outline,
      index: Int,
      arrayScope: List[Int]
  ): (Int, Outline) =
    children
      .foldLeft((line, found, index)) { case ((current, acc, next), child) =>
        child match {
          case value: AbstractConfigNodeValue =>
            val (after, outline) = walk(value, at :+ KeyPath.Segment.Index(next), current, acc, arrayScope)
            (after, outline, next + 1)
          case other => (current + newlines(other.render), acc, next)
        }
      }
      .match { case (end, outline, _) => (end, outline) }

  private def walkField(
      field: ConfigNodeField,
      at: List[KeyPath.Segment],
      line: Int,
      found: Outline,
      arrayScope: List[Int]
  ): (Int, Outline) = {
    val segments = pathOf(field.path)
    val path     = at ++ segments
    // Every field leaves one definition of its own behind, so the count of what stands in `found`
    // has grown by one per field passed: it says where this statement stands in the source.
    val position = found.definitions.size + found.includes.size
    // `+=` is the self-referential `${?key} [ ... ]` in another spelling: it reads the earlier
    // value, which the parser only desugars in the merged tree, not in this document tree.
    val holds    = appends(field) || holdsSubstitution(field.value)
    val contents =
      if (placeholder(segments)) found.add(Include(line, position, KeyPath(at), arrayScope))
      else {
        val own = Definition(
          KeyPath(path),
          line,
          position,
          objectValued = objectValued(field.value),
          holdsSubstitution = holds,
          writesNothing = writesNothing(field.value),
          arrayScope = arrayScope
        )
        found.addAll(implicitObjects(at, segments, line, position, holds, arrayScope)).add(own)
      }
    // The path and the separator only advance the line; the value may hold fields of its own.
    field.children.asScala.toList.foldLeft((line, contents)) { case ((current, acc), child) =>
      child match {
        case value: AbstractConfigNodeValue => walk(value, path, current, acc, arrayScope)
        case other                          => (current + newlines(other.render), acc)
      }
    }
  }

  /** The masked form of an include directive is a field whose key and value spell the placeholder
    * name, so a field named like that is an include and not a definition of the user's.
    */
  private def placeholder(segments: List[KeyPath.Segment]): Boolean = segments match {
    case List(KeyPath.Segment.Name(name)) =>
      name.startsWith(IncludeMasking.PlaceholderPrefix) && {
        val index = name.substring(IncludeMasking.PlaceholderPrefix.length)
        index.nonEmpty && index.forall(_.isDigit)
      }
    case _ => false
  }

  /** A dotted path writes the objects on the way to its leaf too: `a.b = 2` defines `a` as an
    * object, which a later `a = 1` replaces, leaf and all.
    */
  private def implicitObjects(
      at: List[KeyPath.Segment],
      segments: List[KeyPath.Segment],
      line: Int,
      position: Int,
      holdsSubstitution: Boolean,
      arrayScope: List[Int]
  ): Vector[Definition] =
    (1 until segments.size).toVector.map { length =>
      Definition(
        KeyPath(at ++ segments.take(length)),
        line,
        position,
        objectValued = true,
        holdsSubstitution = holdsSubstitution,
        // A leaf stands below it, so it writes something.
        writesNothing = false,
        arrayScope = arrayScope
      )
    }

  /** Whether the later definition leaves nothing of the earlier one. A later value that holds no
    * substitution replaces an earlier one, except that an object written over an earlier object —
    * or over an earlier substitution, whose object merges with it — takes nothing away: `a = ${b}`
    * then `a.c = 2` resolves to b's fields with c beside them.
    */
  private[hoconfmt] def erases(later: Definition, earlier: Definition): Boolean =
    !later.holdsSubstitution && (!later.objectValued || (!earlier.objectValued && !earlier.holdsSubstitution))

  private def prefixes(path: KeyPath): Vector[KeyPath] =
    (1 to path.segments.size).toVector.map(length => KeyPath(path.segments.take(length)))

  private def report(definitions: Vector[Definition]): List[Finding] = {
    val byPath = definitions.zipWithIndex.groupBy { case (definition, _) =>
      (definition.arrayScope, definition.keyPath)
    }
    val killed = definitions.zipWithIndex.flatMap { case (earlier, at) =>
      prefixes(earlier.keyPath)
        .flatMap(path => byPath.getOrElse((earlier.arrayScope, path), Vector.empty))
        .filter { case (later, next) =>
          next > at && (if (later.keyPath == earlier.keyPath) erases(later, earlier)
                        else !later.objectValued && !later.holdsSubstitution)
        }
        .minByOption(_._2)
        .map { case (later, _) => (earlier, later) }
    }
    // One statement can define both an object and its leaves. When the whole statement dies at
    // the same line, the ancestor warning covers it; leaves on their own lines still get theirs.
    val covered = killed.map { case (earlier, later) =>
      (earlier.arrayScope, earlier.line, later.line, earlier.keyPath)
    }.toSet
    killed
      .filterNot { case (earlier, later) =>
        prefixes(earlier.keyPath).dropRight(1).exists { path =>
          covered.contains((earlier.arrayScope, earlier.line, later.line, path))
        }
      }
      .map { case (earlier, later) => Finding.KeyDefinedAgain(earlier.keyPath, earlier.line, later.line) }
      .toList
  }

  /** The path a field writes. A quoted key is one segment however many dots it holds; the period
    * separates elements only outside quotes, exactly as sconfig reads it.
    */
  private def pathOf(path: ConfigNodePath): List[KeyPath.Segment] = {
    val (elements, last) = path.tokens.asScala.toList.foldLeft((List.empty[String], "")) {
      case ((done, current), token) =>
        if (Tokens.isIgnoredWhitespace(token)) (done, current)
        else if (Tokens.isUnquotedText(token)) {
          val text = Tokens.getUnquotedText(token)
          if (text == ".") (done :+ current, "") else (done, current + text)
        } else if (Tokens.isValue(token)) (done, current + unquoted(token))
        else (done, current)
    }
    (elements :+ last).map(KeyPath.Segment.Name(_))
  }

  private def appends(field: ConfigNodeField): Boolean =
    field.children.asScala.exists {
      case single: ConfigNodeSingleToken => single.token.tokenText == Tokens.PLUS_EQUALS.tokenText
      case _                             => false
    }

  private def unquoted(token: Token): String = Tokens.getValue(token).unwrapped match {
    case text: String => text
    case _            => ""
  }

  private def objectValued(value: ConfigNode): Boolean = value match {
    case _: ConfigNodeObject => true
    // An object written beside another merges with it rather than replacing it.
    case concat: ConfigNodeConcatenation => concat.children.asScala.exists(objectValued)
    case _                               => false
  }

  /** Whether an object value holds no value inside it. Such a value adds nothing to a merge, so
    * replacing a value that is not an object is the only work it does.
    */
  private def writesNothing(value: ConfigNode): Boolean = value match {
    case objectValue: ConfigNodeObject =>
      objectValue.children.asScala.forall {
        case field: ConfigNodeField => writesNothing(field.value)
        case _                      => true
      }
    // An object written beside another merges with it, so a concatentation writes nothing when
    // none of its parts does.
    case concat: ConfigNodeConcatenation =>
      concat.children.asScala.forall {
        case part: AbstractConfigNodeValue => writesNothing(part)
        case _                             => true
      }
    case _ => false
  }

  private def holdsSubstitution(node: ConfigNode): Boolean = node match {
    case simple: ConfigNodeSimpleValue   => Tokens.isSubstitution(simple.token)
    case complex: ConfigNodeComplexValue => complex.children.asScala.exists(holdsSubstitution)
    case _                               => false
  }

  private def newlines(text: String): Int = text.count(_ == '\n')
}
