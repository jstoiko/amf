package amf.parser

import org.mulesoft.lexer.{InputRange, TokenData}
import org.yaml.lexer.YamlToken._
import org.yaml.lexer.{YamlToken, YeastToken}
import org.yaml.model._
import amf.common.core.Strings
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * AST emitter
  */
case class ASTEmitter() {

  private var stack               = List(new Builder)
  private def current             = stack.head
  private var document: YDocument = _

  class Builder {
    val tokens = new ArrayBuffer[YeastToken]
    val parts  = new ArrayBuffer[YPart]
  }

  /** Build and return a YDocument node. */
  def document(parse: () => Unit): YDocument = {
    process(BeginDocument)
    parse()
    process(EndDocument)
    document
  }

  def sequence(parse: () => Unit): this.type = {
    process(BeginNode)
    process(BeginSequence)
    parse()
    process(EndSequence)
    process(EndNode)
    this
  }

  def mapping(parse: () => Unit): this.type = {
    process(BeginNode)
    process(BeginMapping)
    parse()
    process(EndMapping)
    process(EndNode)
    this
  }

  def entry(parse: () => Unit): this.type = {
    process(BeginPair)
    parse()
    process(EndPair)
    this
  }

  def scalar(text: String, tag: YTag): this.type = {
    process(BeginNode)
    process(BeginScalar)
    process(EndScalar, text)
    process(EndNode)
    this
  }

  private def process(token: YamlToken, text: String = ""): Unit = {
    token match {
      case BeginDocument =>
        addNonContent(current.parts)
        addToken(token)
      case EndDocument =>
        addToken(token)
        document = new YDocument(buildParts())
      case BeginNode | BeginSequence | BeginScalar | BeginMapping | BeginPair | BeginAlias | BeginAnchor | BeginTag =>
        push()
        addToken(token)
      case EndSequence =>
        addToken(token)
        pop(new YSequence(buildParts()))
      case EndNode =>
        addToken(token)
        pop(YNode(buildParts(), mutable.Map()))
      case EndScalar =>
        addToken(token)
        pop(new YScalar(text, true, getTokens))
      case EndMapping =>
        addToken(token)
        pop(new YMap(buildParts()))
      case EndPair =>
        addToken(token)
        pop(YMapEntry(buildParts()))
      case EndTag =>
        addToken(token)
        pop(YTag(text, getTokens))
      case _ =>
        addToken(token)
    }
  }

  private def pop(part: YPart): Unit = {
    stack = stack.tail
    current.parts += part
  }

  private def addToken(token: YamlToken) = addToken(TokenData(token, InputRange.Zero, 0, 0))

  private def push(): Unit = {
    addNonContent(current.parts)
    stack = new Builder :: stack
  }

  private def buildParts(): IndexedSeq[YPart] = {
    val ps = current.parts
    addNonContent(ps)
    if (ps.isEmpty) IndexedSeq.empty
    else {
      val r = ps.toArray[YPart]
      ps.clear()
      r
    }
  }

  private def addNonContent(buffer: mutable.Buffer[YPart]) = {
    val tks = getTokens
    if (tks.nonEmpty) buffer += new YNonContent(tks)
  }

  private def getTokens: IndexedSeq[YeastToken] = {
    val tks = current.tokens
    if (tks.isEmpty) IndexedSeq.empty
    else {
      val r = tks.toArray[YeastToken]
      tks.clear()
      r
    }
  }

  private def addToken(td: TokenData[YamlToken]) = current.tokens += YeastToken(td.token, td.start, td.end, td.range)
}
