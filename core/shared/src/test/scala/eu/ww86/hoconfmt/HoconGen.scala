package eu.ww86.hoconfmt

import org.scalacheck.Gen

/** Random HOCON documents, generated as a tree that knows its own comments and includes and then
  * written the ways people write HOCON: `:`, `=` or nothing before `{`, both comment markers,
  * quoted and dotted keys, ragged spacing.
  *
  * Knowing the comments and includes up front is what lets a property check the output without
  * parsing it with the code under test. Only constructs the formatter supports are generated, so a
  * failing property is a formatter bug or a new sconfig defect, not a malformed input.
  */
object HoconGen {

  /** `joined` writes the node on the line of the one before it, after a comma. A comment ends its
    * line, so nothing is joined to one.
    */
  enum Node {
    case Field(key: String, separator: String, value: Value, joined: Boolean = false)
    case Comment(marker: String, text: String)
    case Include(statement: String, joined: Boolean = false)
  }

  enum Value {
    case Scalar(text: String)
    case Array(items: List[Value])
    case Object(nodes: List[Node])
  }

  final case class Document(nodes: List[Node]) {

    def text: String = render(nodes, indent = "") + "\n"

    def comments: List[String] = collect(nodes) { case Node.Comment(_, text) => text }

    def includes: List[String] = collect(nodes) { case Node.Include(statement, _) => statement }

    /** sconfig drops a comment with no field after it, which the formatter must refuse; documents
      * without one are those it has no reason to refuse.
      */
    def everyCommentPrecedesAField: Boolean = lists(nodes).forall { level =>
      val lastField = level.lastIndexWhere(_.isInstanceOf[Node.Field])
      level.zipWithIndex.forall { case (node, i) => !node.isInstanceOf[Node.Comment] || i < lastField }
    }

    /** A one-field object inside an array that cannot be rendered on one line, because it holds a
      * substitution, or its field is an object and it holds a comment: sconfig renders it without
      * its braces (see SconfigDefectsSpec), so the formatter rightly refuses it.
      */
    def hitsKnownSconfigDefect: Boolean = arrayItems(nodes).exists {
      case Value.Object(inner) =>
        inner.collect { case field: Node.Field => field } match {
          case List(field) => holdsSubstitution(inner) || (isObjectValued(field) && holdsComment(inner))
          case _           => false
        }
      case _ => false
    }

    /** An include written on a line that holds another field or include. sconfig leaves fields on
      * one line in no defined order, so the formatter has reason to refuse these.
      */
    def includeSharesALine: Boolean = lists(nodes).exists { level =>
      def joinedToPrevious(at: Int) = level.indices.contains(at) && isJoined(level(at), level.lift(at - 1))
      level.indices.exists(i => level(i).isInstanceOf[Node.Include] && (joinedToPrevious(i) || joinedToPrevious(i + 1)))
    }

    /** For each include, the paths of what is written before it in its object: the keys of its
      * earlier fields down to their leaves, and the earlier includes beside it. The order in which
      * a later definition wins is the order of the source, which is what the formatter must keep.
      */
    def keysBeforeIncludes: Map[String, Set[List[String]]] = HoconGen.keysBeforeIncludes(nodes, Nil)

    override def toString: String = s"\n$text"
  }

  def isJoined(node: Node, previous: Option[Node]): Boolean = {
    val joined = node match {
      case Node.Field(_, _, _, joined) => joined
      case Node.Include(_, joined)     => joined
      case _                           => false
    }
    joined && previous.exists(!_.isInstanceOf[Node.Comment])
  }

  // A quoted key is one segment, a dotted one is a path.
  def segments(key: String): List[String] =
    if (key.startsWith("\"")) List(key.stripPrefix("\"").stripSuffix("\"")) else key.split('.').toList

  def includeKey(statement: String): List[String] = List(s"include $statement")

  def includesIn(value: Value, at: List[String]): Map[String, Set[List[String]]] = value match {
    case Value.Object(inner) => keysBeforeIncludes(inner, at)
    case Value.Array(items)  => items.zipWithIndex.flatMap { case (item, i) => includesIn(item, at :+ i.toString) }.toMap
    case Value.Scalar(_)     => Map.empty
  }

  def keysBeforeIncludes(nodes: List[Node], at: List[String]): Map[String, Set[List[String]]] = {
    // An array is a value of its own, and the objects in it are reached by their position.
    def leaves(path: List[String], value: Value): Set[List[String]] = value match {
      case Value.Object(inner) =>
        inner.flatMap { case Node.Field(key, _, v, _) => leaves(path ++ segments(key), v); case _ => Nil }.toSet
      case Value.Array(items) =>
        Set(path) ++ items.zipWithIndex.flatMap { case (item, i) =>
          leaves(path :+ i.toString, item) - (path :+ i.toString)
        }
      case _ => Set(path)
    }
    val (_, found) = nodes.foldLeft((Set.empty[List[String]], Map.empty[String, Set[List[String]]])) {
      case ((before, acc), Node.Field(key, _, value, _)) =>
        val path   = at ++ segments(key)
        val nested = includesIn(value, path)
        (before ++ leaves(path, value), acc ++ nested)
      case ((before, acc), Node.Include(statement, _)) =>
        (before + includeKey(statement), acc + (statement -> before))
      case (state, _) => state
    }
    found
  }

  // ---- rendering -------------------------------------------------------------------------------

  def render(nodes: List[Node], indent: String): String =
    nodes.zipWithIndex.map { case (node, i) =>
      if (isJoined(node, nodes.lift(i - 1))) s", ${render(node, "")}"
      else s"${if (i > 0) "\n" else ""}${render(node, indent)}"
    }.mkString

  def render(node: Node, indent: String): String = node match {
    case Node.Field(key, separator, value, _) => s"$indent$key$separator${render(value, indent)}"
    case Node.Comment(marker, text)           => s"$indent$marker $text"
    case Node.Include(statement, _)           => s"$indent$statement"
  }

  def render(value: Value, indent: String): String = value match {
    case Value.Scalar(text)                   => text
    case Value.Array(items)                   => items.map(render(_, indent)).mkString("[", ", ", "]")
    case Value.Object(nodes) if nodes.isEmpty => "{}"
    case Value.Object(nodes)                  => s"{\n${render(nodes, indent + "  ")}\n$indent}"
  }

  def lists(nodes: List[Node]): List[List[Node]] =
    nodes :: nodes.flatMap {
      case Node.Field(_, _, value, _) => listsIn(value)
      case _                          => Nil
    }

  def listsIn(value: Value): List[List[Node]] = value match {
    case Value.Object(nodes) => lists(nodes)
    case Value.Array(items)  => items.flatMap(listsIn)
    case Value.Scalar(_)     => Nil
  }

  def arrayItems(nodes: List[Node]): List[Value] = {
    def in(value: Value): List[Value] = value match {
      case Value.Array(items)  => items ++ items.flatMap(in)
      case Value.Object(inner) => arrayItems(inner)
      case Value.Scalar(_)     => Nil
    }
    nodes.flatMap {
      case Node.Field(_, _, value, _) => in(value)
      case _                          => Nil
    }
  }

  def holdsSubstitution(nodes: List[Node]): Boolean = {
    def in(value: Value): Boolean = value match {
      case Value.Scalar(text)  => text.startsWith("${")
      case Value.Array(items)  => items.exists(in)
      case Value.Object(inner) => holdsSubstitution(inner)
    }
    nodes.exists {
      case Node.Field(_, _, value, _) => in(value)
      case _                          => false
    }
  }

  def holdsComment(nodes: List[Node]): Boolean = collect(nodes) { case c: Node.Comment => c }.nonEmpty

  // A dotted key is an object holding the rest of the path.
  def isObjectValued(field: Node.Field): Boolean =
    field.value.isInstanceOf[Value.Object] || (!field.key.startsWith("\"") && field.key.contains('.'))

  def collect[A](nodes: List[Node])(pick: PartialFunction[Node, A]): List[A] =
    lists(nodes).flatten.collect(pick)

  // ---- generators ------------------------------------------------------------------------------

  val reserved = Set("include", "true", "false", "null", "yes", "no", "on", "off")

  val word: Gen[String] =
    Gen.choose(1, 8).flatMap(Gen.listOfN(_, Gen.alphaLowerChar)).map(_.mkString).map { w =>
      if (reserved(w)) w + "x" else w
    }

  val key: Gen[String] = Gen.frequency(
    6 -> word,
    1 -> Gen.listOfN(2, word).map(_.mkString(".")),
    1 -> Gen.listOfN(2, word).map(words => words.mkString("\"", " ", "\""))
  )

  /** Text a string or a comment might hold, including what the formatter must not mistake for
    * code: quotes, comment markers, and the word include followed by a target.
    */
  def textOf(pieces: Gen[String]): Gen[String] =
    Gen.choose(0, 6).flatMap(Gen.listOfN(_, pieces)).map(_.mkString.trim)

  val quoted: Gen[String] =
    textOf(
      Gen.frequency(
        8 -> word.map(_ + " "),
        1 -> Gen.const("\\\""),
        1 -> Gen.const("# "),
        1 -> Gen.const("// "),
        1 -> Gen.const("include \\\"x.conf\\\" ")
      )
    ).map(s => s"\"$s\"")

  val scalar: Gen[Value] = Gen
    .frequency(
      3 -> Gen.choose(-1000, 100000).map(_.toString),
      1 -> Gen.choose(0, 99).map(n => s"$n.5"),
      1 -> Gen.oneOf("true", "false", "null"),
      3 -> quoted,
      2 -> word,
      1 -> Gen.const("${?HOCON_GEN_UNSET}")
    )
    .map(Value.Scalar(_))

  val comment: Gen[Node] = for {
    marker <- Gen.oneOf("#", "//")
    text   <- textOf(
              Gen.frequency(
                8 -> word.map(_ + " "),
                1 -> Gen.const("\" "),
                1 -> Gen.const("{ "),
                1 -> Gen.const("} "),
                1 -> Gen.const("include \"x.conf\" ")
              )
            )
  } yield Node.Comment(marker, text)

  val include: Gen[Node] = for {
    name   <- word
    target <- Gen.oneOf(
                s"\"$name.conf\"",
                s"required(\"$name.conf\")",
                s"file(\"$name.conf\")",
                s"classpath(\"$name.conf\")",
                s"url(\"https://example.com/$name.conf\")"
              )
    joined <- joinedFlag(includes = true)
  } yield Node.Include(s"include $target", joined)

  // Only where includes are generated: fields sharing a line with each other is a defect of its
  // own, which would trip the properties that have nothing to do with includes.
  def joinedFlag(includes: Boolean): Gen[Boolean] =
    if (includes) Gen.frequency(3 -> false, 1 -> true) else Gen.const(false)

  def value(depth: Int): Gen[Value] =
    if (depth <= 0) scalar
    else Gen.frequency(6 -> scalar, 1 -> Gen.lzy(array(depth - 1)), 2 -> Gen.lzy(obj(depth - 1, includes = true)))

  def array(depth: Int): Gen[Value] = Gen.choose(0, 4).flatMap(Gen.listOfN(_, value(depth))).map(Value.Array(_))

  def obj(depth: Int, includes: Boolean): Gen[Value] =
    Gen.choose(0, 4).flatMap(Gen.listOfN(_, node(depth, includes))).map(Value.Object(_))

  def field(depth: Int, includes: Boolean): Gen[Node] = for {
    k         <- key
    v         <- if (includes) value(depth) else value(depth).map(withoutIncludes)
    separator <- v match {
                   case Value.Object(_) => Gen.oneOf(" ", " : ", ":", " = ", "  =  ")
                   case _               => Gen.oneOf(" : ", ":", " = ", "=", "  :  ")
                 }
    joined <- joinedFlag(includes)
  } yield Node.Field(k, separator, v, joined)

  def node(depth: Int, includes: Boolean): Gen[Node] =
    if (includes) Gen.frequency(8 -> field(depth, includes), 2 -> comment, 1 -> include)
    else Gen.frequency(8          -> field(depth, includes), 2 -> comment)

  def withoutIncludes(value: Value): Value = value match {
    case Value.Object(nodes) =>
      Value.Object(nodes.collect {
        case Node.Field(k, s, v, _) => Node.Field(k, s, withoutIncludes(v))
        case c: Node.Comment        => c
      })
    case Value.Array(items) => Value.Array(items.map(withoutIncludes))
    case scalar             => scalar
  }

  /** With `distinctKeys`, no field replaces or merges into another. Otherwise a later definition of
    * a key may replace an object, and everything written inside it, which is the formatter's
    * business to notice.
    */
  def documents(includes: Boolean, distinctKeys: Boolean = false): Gen[Document] =
    Gen.choose(0, 8).flatMap(Gen.listOfN(_, node(2, includes))).map { nodes =>
      Document(if (distinctKeys) withDistinctKeys(nodes) else nodes)
    }

  /** Independent array pieces may reuse element field names; neither replaces the other. */
  val documentsWithConcatenations: Gen[Document] = for {
    doc           <- documents(includes = true, distinctKeys = true)
    key           <- word.map(_ + "_concat")
    values        <- Gen.listOfN(2, Gen.choose(-1000, 1000))
    selfReference <- Gen.oneOf(true, false)
  } yield {
    val arrays = values.map(n => s"[{b=$n}]")
    val value  = if (selfReference) arrays.mkString(s"\n$key = $${$key} ") else arrays.mkString(" ")
    Document(doc.nodes :+ Node.Field(key, " = ", Value.Scalar(value)))
  }

  // ---- the include-shadow family ---------------------------------------------------------------

  /** The shapes a definition around an include takes: a value that erases what came before, an
    * object that merges, an empty object, a path below the key, an array — at one key, and under a
    * multi-segment path spelled dotted, nested, or one of each. The path matters as much as the
    * shape: `p.q = 3` writes the object `p` and the value `p.q` on one line, so whether the merge
    * kept something on that line says nothing about which of the two it kept.
    *
    * An element holding a comma carries two statements on one line: a value the merge drops beside
    * the definition that keeps the path an object — `p = 3, p.c = 7` writes the value `p` and the
    * object `p` down to its leaf on one line. The pair is what the one-statement shapes cannot
    * express, and it is what an empty object after the include needs to be dangerous without a
    * concatenation: the empty object's own line becomes the merged value's line when nothing keeps
    * the path before the include (so the pair `p = 3` / `p {}` renders the empty object after the
    * include, values unchanged — instead the joined spelling moves the include and is refused),
    * while a kept definition before the include fixes the merged line there, and then the empty
    * object sharing that line looks kept and lets the included values through.
    */
  val shadowDefinitions: List[String] =
    List(
      "p = 3",
      "p { a = 1 }",
      "p {}",
      "p.c = 7",
      "p = null",
      "p = [1]",
      "p.q = 3",
      "p.q { a = 1 }",
      "p.q {}",
      "p.q.c = 7",
      "p.q = null",
      "p { q = 3 }",
      "p.q.r = 3",
      "p.q.r.c = 7",
      "p = 3, p.c = 7",
      "p = null, p.c = 7",
      "p = 3, p { c = 7 }",
      "p.q = 3, p.q.c = 7"
    )

  /** What the file the include names holds when it writes the same path, and what it holds when it
    * writes something else — at one key, and under the multi-segment paths above.
    */
  val shadowBodies: List[String] =
    List(
      "p = 9",
      "p { b = 9 }",
      "p {}",
      "p.c = 9",
      "q = 1",
      "p.q = 9",
      "p.q { b = 9 }",
      "p.q.c = 9",
      "p.q.r { b = 9 }"
    )

  val shadowInclude: String = "include \"inc.conf\""

  /** One case: the text, the layout it is written in, and what the file it includes holds. The
    * layout is carried along because the resolve suites check one layout per test: the family is
    * large, and a test's own timeout should answer for one layout rather than for all of them.
    */
  final case class ShadowCase(text: String, layout: Layout, includeBody: String)

  /** How a case's statements stand: each on a line of its own, the definitions sharing one line,
    * every statement on one line with the file's closing newline left out, or the case in the third
    * piece of an array concatenation, `rows=[{...}] [{...}] [{\n...\n}]`. A definition in a later
    * piece counts its position inside that piece, which is not the position the merged list carries
    * — the check must not read the merged list by the document's path.
    */
  enum Layout derives CanEqual {
    case OwnLines
    case DefinitionsJoined
    case OneLine
    case ConcatenatedSegments
  }

  /** The lines of a case, each a group of statements joined with a comma where the layout puts
    * them on one line.
    */
  def shadowLines(definitions: List[String], at: Int, layout: Layout): List[List[String]] = {
    val (before, after) = (definitions.take(at), definitions.drop(at))
    layout match {
      case Layout.OwnLines | Layout.ConcatenatedSegments =>
        before.map(List(_)) ++ List(List(shadowInclude)) ++ after.map(List(_))
      case Layout.DefinitionsJoined =>
        List(before).filter(_.nonEmpty) ++ List(List(shadowInclude)) ++ List(after).filter(_.nonEmpty)
      case Layout.OneLine => List(before ++ List(shadowInclude) ++ after)
    }
  }

  /** The layouts worth generating for a placement: sharing a line only says something new where
    * there are two definitions to put on it, and the joined layout repeats the plain one when the
    * include splits them. The concatenation is only worth generating where a definition stands
    * after the include, since a piece's shifted positions matter for a definition the formatting
    * may drop and the include may then reach.
    */
  def shadowLayouts(definitions: List[String], at: Int): List[Layout] = {
    val layouts =
      if (definitions.size < 2 || at == 1) List(Layout.OwnLines, Layout.OneLine)
      else List(Layout.OwnLines, Layout.DefinitionsJoined, Layout.OneLine)
    if (at < definitions.size) layouts :+ Layout.ConcatenatedSegments else layouts
  }

  /** Every way one or two of the shapes stand around an include, in every layout, with every body
    * beside it, at the file root and inside an object. The included file cannot be read at format
    * time — a web page has no filesystem and the target may be a URL — so a definition there may
    * be what kept its values out, and the formatter must either refuse the case or hand back text
    * that still resolves the same way. The suites that can resolve write the file named by
    * `shadowInclude` beside the text and check exactly that.
    */
  lazy val shadowCases: List[ShadowCase] = {
    def around(definitions: List[String], at: Int, layout: Layout): String = {
      val lines = shadowLines(definitions, at, layout).filter(_.nonEmpty)
      layout match {
        case Layout.ConcatenatedSegments =>
          // Two pieces before the one holding the case: its element is not the merged list's
          // first, so the positions its definitions count are shifted. The case itself stays one
          // element, since only definitions beside the include are the check's business.
          def piece(body: String) = s"[{\n$body\n}]"
          val caseLines           = lines.map(_.mkString(", ")).mkString("\n")
          s"rows = ${piece("filler = 1")} ${piece("filler = 2")} ${piece(caseLines)}"
        case _ =>
          val text = lines.map(_.mkString(", ")).mkString("\n")
          text + (if (layout == Layout.OneLine) "" else "\n")
      }
    }

    def insideAnObject(text: String): String =
      "a {\n" + text.stripSuffix("\n").linesIterator.map("  " + _).mkString("\n") + "\n}\n"

    val oneStatement = for {
      definition <- shadowDefinitions
      at         <- 0 to 1
    } yield List(definition) -> at
    val twoStatements = for {
      first  <- shadowDefinitions
      second <- shadowDefinitions
      at     <- 0 to 2
    } yield List(first, second) -> at
    for {
      (definitions, at) <- oneStatement ++ twoStatements
      layout            <- shadowLayouts(definitions, at)
      nested            <- List(false, true)
      body              <- shadowBodies
    } yield {
      val text = around(definitions, at, layout)
      ShadowCase(if (nested) insideAnObject(text) else text, layout, body)
    }
  }

  /** The cases of one layout. */
  def shadowCases(layout: Layout): List[ShadowCase] =
    shadowCases.filter(_.layout == layout)
  def withDistinctKeys(nodes: List[Node]): List[Node] =
    nodes.zipWithIndex.map {
      case (Node.Field(key, separator, value, joined), i) =>
        val (first, rest) = firstSegment(key)
        Node.Field(s"${first}_$i$rest", separator, distinctKeysIn(value), joined)
      case (other, _) => other
    }

  def distinctKeysIn(value: Value): Value = value match {
    case Value.Object(nodes) => Value.Object(withDistinctKeys(nodes))
    case Value.Array(items)  => Value.Array(items.map(distinctKeysIn))
    case scalar              => scalar
  }

  // A quoted key is one segment, closing quote and all; the suffix goes inside the quotes.
  def firstSegment(key: String): (String, String) =
    if (key.startsWith("\"")) (key.dropRight(1), "\"")
    else key.span(_ != '.')
}
