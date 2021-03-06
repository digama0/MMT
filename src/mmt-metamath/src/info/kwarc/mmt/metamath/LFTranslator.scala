package info.kwarc.mmt.metamath

import scala.collection.mutable.HashMap
import scala.collection.mutable.Stack
import scala.collection.mutable.TreeSet
import org.metamath.scala._
import info.kwarc.mmt.api._
import info.kwarc.mmt.api.archives.BuildTask
import info.kwarc.mmt.api.checking.{ Checker, CheckingEnvironment, RelationHandler }
import info.kwarc.mmt.api.documents.{ Document, MRef }
import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.frontend.Logger
import info.kwarc.mmt.api.modules.DeclaredTheory
import info.kwarc.mmt.api.objects.Context
import info.kwarc.mmt.api.objects.OMMOD
import info.kwarc.mmt.api.objects.OMS
import info.kwarc.mmt.api.objects.OMV
import info.kwarc.mmt.api.objects.Term
import info.kwarc.mmt.api.opaque.OpaqueText
import info.kwarc.mmt.api.opaque.StringFragment
import info.kwarc.mmt.api.symbols
import info.kwarc.mmt.lf._
import info.kwarc.mmt.api.objects.OMBIND

class LFTranslator(val controller: Controller, bt: BuildTask, index: Document => Unit) extends Logger {
  def logPrefix = "mm-omdoc"
  protected def report = controller.report

  val path = bt.narrationDPath

  def addDatabase(db: Database): Document = {
    val mod = Metamath.setmm
    val doc = new Document(path, root = true)
    controller add doc
    val theory = new DeclaredTheory(mod.doc, mod.name, Some(Metamath.prelude))
    controller add theory
    controller add MRef(doc.path, theory.path)
    val tr = new LFDBTranslator()(db)
    val checker = controller.extman.get(classOf[Checker], "mmt")
      .getOrElse(throw GeneralError(s"no MMT checker found"))
    implicit val _ = new CheckingEnvironment(new ErrorLogger(report), RelationHandler.ignore)
    // TODO restricted to the first 1000 constants for inspection
    val consts = db.decls.filter { case c: Comment => false case _ => true }
    consts /*.dropRight(consts.length - 1000)*/ foreach {
      case a: Assert =>
        for (t <- tr.translateAssert(a)) {
          controller add symbols.Constant(theory.toTerm, LocalName(a.label), Nil,
            Some(t), tr.translateProof(a), None)
        }
      case c: Comment =>
        controller add new OpaqueText(theory.asDocument.path, List(StringFragment(c.text)))
      case TypecodeDecl(ckey, cval) =>
        controller add symbols.Constant(theory.toTerm, LocalName(ckey.id), Nil,
          Some(if (ckey == cval) Univ(1) else Arrow(OMS(Metamath.setmm ? cval.id), Univ(1))), None, None)
      case _ =>
    }
    checker(theory)
    doc
  }
}

class LFDBTranslator(implicit db: Database) {
  val boundVars = new HashMap[Assert, Array[Option[Array[BVarData]]]]
  val syntaxToDefn = new HashMap[Assert, Assert]
  val alignments = new HashMap[Assert, GlobalName]

  val SET = db.syms("set")
  val DED = db.syms("|-")
  val WN = db.asserts("wn")
  val WI = db.asserts("wi")
  val WB = db.asserts("wb")
  val WAL = db.asserts("wal")
  val WCEQ = db.asserts("wceq")

  boundVars += (
    WN -> Array(None),
    WI -> Array(None, None),
    WB -> Array(None, None),
    WAL -> Array(None, Some(Array(1, 0))),
    WCEQ -> Array(None, None),
    db.asserts("cv") -> Array(Some(Array(2))),
    db.asserts("cab") -> Array(None, Some(Array(1, 0))))

  alignments += (
    WN -> Metamath.wn,
    WI -> Metamath.wi,
    WB -> Metamath.wb,
    //WAL -> Metamath.setmm ? "forall",
    db.asserts("wa") -> Metamath.wa,
    db.asserts("wo") -> Metamath.wo)
  //db.asserts("wtru") -> Metamath.setmm ? "true",
  //db.asserts("wex") -> Metamath.setmm ? "exists",
  //db.asserts("pm3.2i") -> Metamath.setmm ? "andI",
  //db.asserts("simpli") -> Metamath.setmm ? "andEl",
  //db.asserts("simpri") -> Metamath.setmm ? "andEr",
  //db.asserts("orci") -> Metamath.setmm ? "orIl",
  //db.asserts("olci") -> Metamath.setmm ? "orIr",
  //db.asserts("impl") -> Metamath.setmm ? "_impl")

  db.decls foreach {
    case a: Assert if a.proofUnparsed.isEmpty => processBoundVars(a)
    case _ =>
  }

  implicit def toBVarData(i: Int): BVarData = new BVarData(i.toByte)
  case class BVarData(var b: Byte = 0) {
    def |=(other: BVarData) = {
      b = (b | other.b).toByte
      this
    }
    def free = |=(2)
    def isBound = (b & 1) != 0
    def isFree = (b & 2) != 0
    def bind: BVarData = if (b != 0) 1 else 0
  }

  def getBoundVars(syntax: Assert): Array[Option[Array[BVarData]]] = {
    boundVars.getOrElseUpdate(syntax, {
      for (h <- syntax.hyps if h.typecode == SET)
        throw MMError(s"Syntax axiom '$syntax' is not associated to a definition. Please add it"
          + " to the exception list.")
      Array.fill(syntax.hyps.length)(None)
    })
  }

  private def newSet: TreeSet[Floating] = new TreeSet[Floating]()(Ordering.by(_.seq))

  def processBoundVars(defn: Assert) {
    for (h <- defn.hyps if h.isInstanceOf[Essential]) return
    defn.parse match {
      case AssertNode(eq, List(AssertNode(a, l), b)) if (eq == WB || eq == WCEQ) =>
        val used = newSet
        if (l forall {
          case HypNode(v: Floating) => used.add(v)
          case _ => false
        }) {
          val parameters = used.toArray
          boundVars.put(a, Array.tabulate(parameters.length)(i => {
            val Target = parameters(i)
            if (Target.typecode == SET) {
              val arr = Array.fill[BVarData](parameters.length)(0)
              def processInto(out: Array[BVarData], p: ParseTree) {
                p match {
                  case HypNode(v: Floating) =>
                    parameters.indexOf(v) match {
                      case -1 =>
                      case i => out(i).free
                    }
                  case AssertNode(ax, child) =>
                    var setIndex = -1
                    for ((c, i) <- child.zipWithIndex) c match {
                      case HypNode(Target) =>
                        if (setIndex != -1)
                          throw MMError(s"Definition $defn uses variable $Target twice in " +
                            "the same syntax node, which is not supported by this algorithm.")
                        setIndex = i
                      case _ =>
                    }
                    if (setIndex == -1) child.foreach(processInto(out, _))
                    else {
                      val bv = getBoundVars(ax)(setIndex).get
                      for ((b, c) <- bv zip child) {
                        if (b.isBound) {
                          val inner = Array.fill[BVarData](out.length)(0)
                          processInto(inner, c)
                          for ((o, i) <- out zip inner) o |= i.bind
                        }
                        if (b.isFree) processInto(out, c)
                      }
                    }
                }
              }

              processInto(arr, b)
              Some(arr)
            } else None
          }))
          syntaxToDefn.put(a, defn)
        }
      case _ =>
    }
  }

  def align(a: Assert): Term =
    OMS(alignments.getOrElse(a, Metamath.setmm ? a.label))

  type DependVars = HashMap[Floating, TreeSet[Floating]]

  def getDependVars(stmt: Assert): DependVars = {
    implicit val dependVars = new DependVars
    stmt.hyps foreach {
      case h: Floating => dependVars.put(h, newSet)
      case _ =>
    }
    val scan = new ScanExpr
    stmt.hyps foreach {
      case e: Essential => scan.scan(e.parse)
      case _ =>
    }
    scan.scan(stmt.parse)
    for ((x, y) <- stmt.disjoint) {
      if (x.activeFloat.typecode != SET)
        dependVars(x.activeFloat) -= y.activeFloat
      else if (y.activeFloat.typecode != SET)
        dependVars(y.activeFloat) -= x.activeFloat
    }
    dependVars
  }

  val LF_SET = OMS(Metamath.set)

  def LF_type(s: Statement): Term = s.typecode.id match {
    case "wff" => OMS(Metamath.wff)
    case _ => OMS(Metamath.setmm ? s.typecode.id)
  }

  val LF_DED = OMS(Metamath.|-)

  def LF_var(v: Floating): LocalName = LocalName(v.v.id)

  def reducedLambda(name: LocalName, tp: Term, body: Term): Term = body match {
    case Apply(left, OMV(v)) if name == v => left
    case _ => curryLambda(name, tp, body)
  }
  def curryApply(left: Term, tl: Term): Term = left match {
    case ApplySpine(f, a) => ApplySpine(f, a :+ tl: _*)
    case _ => Apply(left, tl)
  }
  def curryPi(name: LocalName, tp: Term, body: Term) = body match {
    case OMBIND(Pi.term, con, rest) => OMBIND(Pi.term, OMV(name) % tp :: con, rest)
    case _ => Pi(name, tp, body)
  }
  def curryLambda(name: LocalName, tp: Term, body: Term) = body match {
    case OMBIND(Lambda.term, con, rest) => OMBIND(Lambda.term, OMV(name) % tp :: con, rest)
    case _ => Lambda(name, tp, body)
  }

  def needsFree(b: Array[BVarData], i: Int, child: List[ParseTree]) = b(i).isFree ||
    child.zipWithIndex.exists { case (c, j) => b(j).isBound && b(j).isFree && c.stmt.typecode != SET }

  def translateAssert(a: Assert): Option[Term] = {
    if (a.typecode != DED) {
      if (!a.syntax) return None
      val bv = getBoundVars(a)
      a.parse match {
        case AssertNode(ax, child) =>
          Some(child.zipWithIndex.foldRight(LF_type(a))((k, t) => k match {
            case (node, i) => bv(i) match {
              case Some(b) => if (needsFree(b, i, child)) Arrow(LF_SET, t) else t
              case _ =>
                Arrow(bv.foldRight(LF_type(node.stmt))((k, ty) => k match {
                  case Some(b) if b(i).isBound => Arrow(LF_SET, ty)
                  case _ => ty
                }), t)
            }
          }))
      }
    } else {
      implicit val dependVars = getDependVars(a)

      Some(a.hyps.foldRight(translateStmt(a))((h, t) => h match {
        case e: Essential => Arrow(translateStmt(e), t)
        case v: Floating =>
          if (v.typecode == SET) t
          else curryPi(LocalName(v.v.id),
            dependVars(v).foldRight(LF_type(v))((_, ty) => Arrow(LF_SET, ty)), t)
      }))
    }
  }

  def translateStmt(s: Statement)(implicit dependVars: DependVars): Term = {
    val free = newSet
    val scan = new ScanExpr(Some(free))
    scan.scan(s.parse)
    free.foldRight(Apply(LF_DED, translateTerm(s.parse)): Term)((v, t) => curryPi(LF_var(v), LF_SET, t))
  }

  def translateTerm(p: ParseTree)(implicit dependVars: DependVars): Term = p match {
    case HypNode(v: Floating) =>
      if (v.typecode == SET) OMV(LF_var(v))
      else dependVars.getOrElse(v, throw new IllegalArgumentException)
        .foldLeft(OMV(LF_var(v)): Term)((t, v) => curryApply(t, OMV(LF_var(v))))
    case AssertNode(ax, child) =>
      val bv = getBoundVars(ax)
      child.zipWithIndex.foldLeft(align(ax))((ap, k) => k match {
        case (node, i) => bv(i) match {
          case Some(b) =>
            if (needsFree(b, i, child)) node match { case HypNode(v: Floating) => curryApply(ap, OMV(LF_var(v))) }
            else ap
          case _ =>
            curryApply(ap, child.zip(bv).foldRight(translateTerm(node))((k, t) => k match {
              case (HypNode(v: Floating), Some(b)) if b(i).isBound =>
                reducedLambda(LF_var(v), LF_SET, t)
              case _ => t
            }))
        }
      })
  }

  def translateProof(a: Assert): Option[Term] = {
    if (a.typecode == DED) return None // not currently handling proof term translation
    implicit val dependVars = getDependVars(a)
    syntaxToDefn.getOrElse(a, return None).parse match {
      case AssertNode(_, List(AssertNode(_, child), p)) =>
        val bv = getBoundVars(a)
        val t = try {
          translateTerm(p)
        } catch {
          case e: IllegalArgumentException => return None
        }
        Some(child.zipWithIndex.foldRight(t)((k, t) => k match {
          case (HypNode(v: Floating), i) => bv(i) match {
            case Some(b) => if (needsFree(b, i, child)) reducedLambda(LF_var(v), LF_SET, t) else t
            case _ =>
              reducedLambda(LF_var(v), child.zip(bv).foldRight(LF_type(v))((k, ty) => k match {
                case (HypNode(v: Floating), Some(b)) if b(i).isBound => Arrow(LF_SET, ty)
                case _ => ty
              }), t)
          }
        }))
    }
  }

  class ScanExpr(free: Option[TreeSet[Floating]] = None)(implicit val dependVars: DependVars) {
    val stack = new Stack[Floating]

    def scan(p: ParseTree) {
      p match {
        case HypNode(h: Floating) => free match {
          case Some(set) => for (v <- dependVars(h) if !stack.contains(v)) set += v
          case _ if h.typecode == SET => dependVars(h) += h
          case _ => dependVars(h) ++= stack
        }
        case AssertNode(ax, child) =>
          val bv = getBoundVars(ax)
          for ((c, i) <- child.zipWithIndex) bv(i) match {
            case Some(b) => if (needsFree(b, i, child)) scan(c)
            case _ =>
              val oldLen = stack.size
              for ((d, j) <- child.zipWithIndex; arr <- bv(j) if arr(i).isBound)
                d match { case HypNode(v: Floating) => stack push v }
              scan(c)
              while (stack.size > oldLen) stack.pop
          }
      }
    }
  }
}