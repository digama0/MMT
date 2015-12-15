package info.kwarc.mmt.api

import info.kwarc.mmt.api.modules._
import info.kwarc.mmt.api.presentation._
import info.kwarc.mmt.api.symbols._

import scala.xml.Node

/** A StructuralElement is any knowledge item on the document, module, or symbol level.
  *
  * The structural elements are subdivided according to their dimension: content, presentation, or narration.
  */
trait StructuralElement extends Content with metadata.HasMetaData {
  /** the MMT URI of the element */
  def path: Path

  //def governingPath = path match {case c: ContentPath => Some(c) case _ => None}
  /** the containing knowledge item, a URL if none */
  def parent: Path

  /** the children of this element */
  def getDeclarations: List[StructuralElement]

  /** returns all term components of this elements */
  def getComponents: List[DeclarationComponent]

  /** returns a specific component if present */
  def getComponent(k: ComponentKey) = getComponents find (_.key == k) map (_.value)

  private var elaborated = false
  def hasBeenElaborated = elaborated
  def setElaborated {elaborated = true}

  /** If a StructuralElement has been generated (as opposed to being physically present in the document),
    * this gives its origin.
    *
    * The origin must be set by overriding the field when creating the ContentElement.
    */
  private var origin: Origin = Original
  def setOrigin(o: Origin) {
    origin = o
  }
  def getOrigin = origin
  def isGenerated = origin != Original
}

/** the status of a [[ContentElement]] during a parse-check cycle
  *
  * When reading a input that already exists in memory but may have been changed in the source,
  * the old instance is kept for change management.
  */
abstract class ElementStatus

/** a special case beyond the normal state: the element exists in the theory and has been checked.
  *
  * This does not guarantee that the element is logically valid:
  * It only guarantees that all invalidity errors have been reported.
  *
  * Most algorithms can treat this status as equivalent to [[Active]].
  * The main exception are the checking algorithms themselves.
  */
case object Checked extends ElementStatus

/** the default state: the element exists in the MMT content base
  */
case object Active extends ElementStatus

/** a temporary state during parsing set by [[info.kwarc.mmt.api.frontend.Controller.read]]
  *
  * the element does not exist in the theory, but is expected to be recreated during the current parse.
  */
case object Inactive extends ElementStatus

/** A ContentElement is any knowledge item that is used to represent mathematical content.
  *
  * These are the core MMT items such as modules, and symbols.
  * This includes virtual knowledge items.
  */
trait ContentElement extends StructuralElement {
  /** the API may deactivate a ContentElement instead of deleting it to permit reusing it later
    *
    * invariant: API client code may assume that this flag is never set
    */
  var status: ElementStatus = Active

  def path: ContentPath

  /** returns all children of this elements */
  def getDeclarations: List[ContentElement]

  /** recursively applies a function to all declarations in this element (in declaration order) */
  def foreachDeclaration(f: ContentElement => Unit) {
    f(this)
    getDeclarations foreach { d => d.foreachDeclaration(f) }
  }

  /** recursively applies a function to all components in this element (in declaration order) */
  def foreachComponent(f: (CPath, ComponentContainer) => Unit) {
    getComponents foreach { case DeclarationComponent(c, t) => f(path $ c, t) }
    getDeclarations foreach { d => d.foreachComponent(f) }
  }

  /** two ContentElement's are compatible
    * if they have the same type, same Path, and agree in all parts that are TermContainer's
    */
  def compatible(that: ContentElement): Boolean = {
    this.getOrigin == that.getOrigin &&
      ((this, that) match {
        case (a: DeclaredTheory, b: DeclaredTheory) =>
          a.path == b.path && a.meta == b.meta && a.parameters == b.parameters
        case (a: DefinedTheory, b: DefinedTheory) =>
          a.path == b.path && a.parameters == b.parameters
        case (a: View, b: View) =>
          a.getClass == b.getClass && a.path == b.path && a.from == b.from &&
            a.to == b.to && (a.isImplicit == b.isImplicit)
        case (a: NestedModule, b: NestedModule) =>
          a.module.compatible(b.module)
        case (a: Constant, b: Constant) =>
          a.path == b.path && a.alias == b.alias && a.rl == b.rl
        case (a: Structure, b: Structure) =>
          a.getClass == b.getClass && a.path == b.path && (a.isImplicit == b.isImplicit)
        case _ => false
      })
  }
}

/** A NarrativeElement is any OMDoc element that is used to represent narration and document structure.
  *
  * These include documents and cross-references.
  */
trait NarrativeElement extends StructuralElement {
  /** the containing document (if any) */
  def parentOpt: Option[DPath]
  def getComponents = Nil
}

/** The trait Content is mixed into any class that can be rendered.
  */
trait Content {
  /** XML representation */
  def toNode: Node

  /** by default, this prints out toNode
    *
    * potentially large [[StructuralElement]]s should override it with a memory-efficient implementation
    */
  def toNode(rh: RenderingHandler) {
    rh(toNode)
  }
}
