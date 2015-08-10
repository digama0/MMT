package info.kwarc.mmt.leo.AgentSystem.MMTSystem

import info.kwarc.mmt.api.symbols.Constant
import info.kwarc.mmt.api.{Active, modules, RuleSet}
import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.objects._
import info.kwarc.mmt.leo.AgentSystem.{Display, Blackboard}

/**
 * Created by Mark on 7/21/2015.
 *
 * This represents the class of the LF blackboard which handles proofs in the LF prover
 */
class MMTBlackboard(val rules:RuleSet,val goal: Goal)(implicit controller: Controller,oLP:String) extends Blackboard {

  val c = controller
  override def logPrefix = oLP + "#MMTBlackboard"

  implicit val presentObj: Obj => String = o => controller.presenter.asString(o)
  override lazy val report = controller.report

  /** convenience function to create a matcher in the current situation */
  def makeMatcher(context: Context, queryVars: Context) = new Matcher(controller, rules, context, queryVars)

  /** Boolean representing the status of the prof goal */
  override def finished: Boolean = goal.isSolved

  /*val invertibleBackward = rules.get(classOf[BackwardInvertible]).toList
  val invertibleForward  = rules.get(classOf[ForwardInvertible]).toList
  val searchBackward     = rules.get(classOf[BackwardSearch]).toList.sortBy(_.priority).reverse
  val searchForward      = rules.get(classOf[ForwardSearch]).toList*/

  val invertibleBackward = List(PiIntroduction)
  val invertibleForward  = Nil
  val searchBackward     = List(BackwardPiElimination)
  val searchForward      = List(ForwardPiElimination)
  val searchTerms        = List(TermGeneration)
  val transitivityRules  = Nil
    //List(TransitivityGeneration)


  log("Rules: " + Display.listDisplay(rules.getAll.toList ))
  log("Invertible Backwards rules:" + invertibleBackward)

  val goalSection = new GoalSection(this,goal)
  addSection(goalSection)
  log("Added Goal of type: " + goal.pretty())

  val shapeDepth = 2
  val factSection = new FactSection(this, shapeDepth)
  addSection(factSection)
  implicit val facts:Facts = factSection.data


  val termSection = new TermSection(this)
  addSection(termSection)
  implicit val terms:Terms = termSection.data

  val transitivitySection = new TransitivitySection(this)
  addSection(transitivitySection)

}



