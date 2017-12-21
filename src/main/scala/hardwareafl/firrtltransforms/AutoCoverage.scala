
package hardwareafl
package firrtltransforms

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.PrimOps._
import firrtl.Utils.{BoolType, get_info}

import scala.collection.mutable

// Removes compound expressions from mux conditions
// Ensures they are a reference
// Borrows from Firrtl's SplitExpressions
class SplitMuxConditions extends Transform {
  def inputForm = MidForm
  def outputForm = MidForm

  private def isRef(expr: Expression): Boolean = expr match {
    case ref @ (_: WRef | _: WSubField | _: WSubIndex) => true
    case _ => false
  }

  private def onModule(mod: Module): Module = {
    val namespace = Namespace(mod)
    def onStmt(s: Statement): Statement = {
      val stmts = mutable.ArrayBuffer.empty[Statement]
      def onExpr(e: Expression): Expression = e map onExpr match {
        case mux @ Mux(cond, tval, fval, mtpe) if !isRef(cond) =>
          val n = DefNode(Utils.get_info(s), namespace.newTemp, cond)
          stmts.append(n)
          Mux(WRef(n.name, cond.tpe, NodeKind, MALE), tval, fval, mtpe)
        case mux: Mux =>
          mux
        case other => other
      }

      stmts += s.map(onExpr).map(onStmt)
      stmts.size match {
        case 1 => stmts.head
        case _ => Block(stmts)
      }
    }
    mod.copy(body = onStmt(mod.body))
  }
  def execute(state: CircuitState): CircuitState = {
    state.copy(circuit = state.circuit.copy(modules = state.circuit.modules map {
      case mod: Module => onModule(mod)
      case ext: ExtModule => ext
    }))
  }
}

// TODO: this is realtively decoupled from the instrumentation pass
//       -> maybe move into separate file
object TomlGenerator {
  case class TestInput(name: String, width: Int)
  case class DebugInfo(filename: String, line: Int, col: Int)
  case class CoverPoint(name: String, dbg: DebugInfo, expr: String)
  def getWidth(port: Port) : Int = {
    port.tpe match {
      case gt: GroundType => {
        gt.width match {
          case iw: IntWidth => iw.width.intValue
          case _ => throw new Exception(s"Type of ${port.name} width is unexpected")
        }
      }
      case _ => throw new Exception(s"Type of ${port.name} is unexpected")
    }
  }
  def getFuzzingInputs(ports: Seq[Port]) : Seq[TestInput] = {
    val possible_inputs : Iterable[Option[TestInput]] =
    for(port <- ports) yield {
      port.direction match {
        case Input => {
          if(port.name != "clock" && port.name != "reset") {
            Some(TestInput(port.name, getWidth(port)))
          } else { None }
        }
        case _ => None
      }
    }
    // sort decending by width (TODO: is sortWith stable?)
    possible_inputs.flatten.toSeq.sortWith(_.width > _.width)
  }
  def parseFileInfo(info: FileInfo) : DebugInfo = {
    val pattern = raw":([^@]+)@(\d+)\.(\d+)".r
    info.info.string match {
      case pattern(filename, line, col) => DebugInfo(filename, line.toInt, col.toInt)
      case _ => throw new Exception(s"Unexpected FileInfo string: ${info.info.string}")
    }
  }
  def getDebugInfo(info: Info) : Seq[Option[DebugInfo]] = {
    info match {
      case fi: FileInfo => Seq(Some(parseFileInfo(fi)))
      case mi: MultiInfo => mi.infos.flatMap(getDebugInfo)
      case _ => Seq()
    }
  }
  def getDebugInfo(stmt: Statement) : DebugInfo = {
    val infos = getDebugInfo(get_info(stmt)).flatten
    infos.headOption match {
      case Some(info) => info
      case None => DebugInfo("", -1, -1)
    }
  }
  def getDefinitions(mod: Module) : Map[String, IsDeclaration] = {
    // TODO: this assumes one global namespace ... is this correct?
    val definitions = collection.mutable.HashMap[String, IsDeclaration]()
    def onStmt(stmt: Statement) : Statement = {
      stmt map onStmt
      stmt match {
        case d: DefNode     => definitions(d.name) = d
        case d: DefWire     => definitions(d.name) = d
        case d: DefRegister => definitions(d.name) = d
        case _ => None
      }
      stmt
    }
    for(port <- mod.ports) {
      definitions(port.name) = port
    }
    mod.body map onStmt
    definitions.toMap
  }
  def isGeneratedIdentifier(id: String) : Boolean = {
    // heuristic
    val pattern = raw"(T_\d+)".r
    id match {
      case pattern(_) => true
      case _ => false
    }
  }
  var definitions: Map[String, IsDeclaration] = Map()
  def getHumanReadableExpression(decl: IsDeclaration) : String = {
    decl match {
      case d: DefNode => getHumanReadableExpression(d.value)
      case d: DefWire => s"Wire(${d.name})"
      case d: DefRegister => s"Reg(${d.name})"
      case p: Port => {
        p.direction match {
          case Input => { s"In(${p.name})" }
          case Output => { s"Out(${p.name})" }
          case d => throw new Exception(s"Unexpected direction ${d}")
        }
      }
    }
  }
  def getHumanReadableExpression(cond: Expression) : String = {
    // this is a *heuristic* to essentially try and undo SplitExpressions
    cond match {
      case ref: WRef => { getHumanReadableExpression(this.definitions(ref.name)) }
      //   if(isGeneratedIdentifier(ref.name)) {
      //     getHumanReadableExpression(this.definitions(ref.name))
      //   } else { ref.name }
      // }
      case lit: Literal => { s"${lit.value}" }
      case doprim: DoPrim => {
        // lot's of code similar to the Verilog Emitter .... hm
        val a0 = getHumanReadableExpression(doprim.args.head)
        val a1 = getHumanReadableExpression(doprim.args(1))
        doprim.op match {
          case Add  => s"(${a0} + ${a1})"
          case Addw => s"(${a0} + ${a1})"
          case Sub  => s"(${a0} - ${a1})"
          case Subw => s"(${a0} - ${a1})"
          case Mul  => s"(${a0} * ${a1})"
          case Div  => s"(${a0} / ${a1})"
          case Rem  => s"(${a0} % ${a1})"
          case Lt   => s"(${a0} < ${a1})"
          case Leq  => s"(${a0} <= ${a1})"
          case Gt   => s"(${a0} > ${a1})"
          case Geq  => s"(${a0} >= ${a1})"
          case Eq   => s"(${a0} == ${a1})"
          case Neq  => s"(${a0} != ${a1})"
          case Neg  => s"-${a0}"
          case Not  => s"(not ${a0})"
          case And  => s"(${a0} and ${a1})"
          case Or   => s"(${a0} or ${a1})"
          case Xor  => s"(${a0} xor ${a1})"
        }
      }
    }
  }
  // def toCoverPoint(cond: WRef, stmt: Statement) : CoverPoint = {
  //   val name = cond.serialize
  //   val dbg = get_info(stmt)
  // }

  def apply(mod: Module, coverage: Seq[(WRef,Statement)]) = {
    this.definitions = getDefinitions(mod)
    //println(s"Definition: ${definitions.keys.toSeq}")
    println(s"Module: ${mod.name}")
    println(s"fuzzing inputs:")
    for(inp <- getFuzzingInputs(mod.ports)) {
      println(s"${inp.name}(${inp.width})")
    }
    for((cond, stmt) <- coverage) {
      val dbg = getDebugInfo(stmt)
      val human = getHumanReadableExpression(cond)
      println(s"Coverage: ${cond.serialize} @ ${dbg.filename}:${dbg.line}: `${human}`")
    }
  }
}


// Important Assumption: We rely on SplitExpressions and CSE doing a good job to prevent duplicate
// cover points. Wires probably prevent all duplicates from being removed
class AutoCoverage extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  // Hardwired constants
  val coveragePortName = "coverageOut"
  val coverPointPrefix = "_COV"

  // Helper functions that probably should be in Firrtl itself
  private def Not(expr: Expression) = {
    require(expr.tpe == BoolType, "You can only invert Boolean expressions!")
    DoPrim(PrimOps.Not, List(expr), List.empty, BoolType)
  }
  private def Cat(exprs: Expression*) = {
    require(exprs.size > 0)
    exprs.tail.foldLeft(exprs.head) { case (next, acc) =>
      DoPrim(PrimOps.Cat, List(next, acc), List.empty, CatTypes(next.tpe, acc.tpe))
    }
  }
  private def CatTypes(tpe1: Type, tpe2: Type): Type = (tpe1, tpe2) match {
    case (UIntType(w1), UIntType(w2)) => UIntType(w1 + w2)
    case (SIntType(w1), SIntType(w2)) => UIntType(w1 + w2)
    case other => throw new Exception(s"Unexpected Types $other!")
  }

  private def onModule(mod: Module): Module = {
    // We require mux conditions to be WRefs, thus SplitExpressions must precede this transform
    // We also assume CSE has removed duplicates
    val muxConds = mutable.LinkedHashMap.empty[WRef,Statement]
    def onExpr(expr: Expression, stmt: Statement): Expression = {
    expr.map((expr: Expression) => onExpr(expr, stmt)) match {
      case mux: Mux =>
        val cond: WRef = mux.cond match {
          case ref: WRef => ref
          case other => throw new Exception(s"Unexpected mux condition that isn't a WRef! $other")
        }
        muxConds += (cond -> stmt)
        mux
      case other => other
    }
    }
    def onStmt(stmt: Statement): Statement = stmt.map(onStmt).map(
      (expr: Expression) => onExpr(expr, stmt))

    // collect Mux conditions
    onStmt(mod.body)
    val numCovPoints = muxConds.size * 2 // we want both sides of each mux condition

    // generate TOML for the fuzzer and the test harness generator
    TomlGenerator(mod, muxConds.toSeq)

    val namespace = Namespace(mod)

    // Add coverage port
    assert(namespace.tryName(coveragePortName))
    val covPort = Port(NoInfo, coveragePortName, Output, UIntType(IntWidth(numCovPoints)))

    // Generate cover points
    val coverPoints: Seq[DefNode] = muxConds.keys.toSeq.flatMap { expr =>
      val trueNode = DefNode(NoInfo, namespace.newName(coverPointPrefix), expr)
      val falseNode = DefNode(NoInfo, namespace.newName(coverPointPrefix), Not(expr))
      List(trueNode, falseNode)
    }

    // Cat cover points and connect to coverage port
    val covConnect = Connect(NoInfo,
      WRef(covPort.name, covPort.tpe, PortKind, MALE),
      Cat(coverPoints.map(node => WRef(node.name, BoolType, NodeKind, MALE)): _*)
    )

    // Add new constructs
    val portsx = mod.ports :+ covPort
    val bodyx = Block(List(mod.body) ++ coverPoints ++ List(covConnect))
    mod.copy(ports = portsx, body = bodyx)
  }
  val cleanupPasses = List(
    // This should be run by the Firrtl Verilog Emitter but currently isn't
    firrtl.passes.SplitExpressions
  )
  def execute(state: CircuitState): CircuitState = {
    require(state.circuit.modules.size == 1,
      "This custom transform currently only works on single Module designs!")
    val splitMux = new SplitMuxConditions
    //val state2 = splitMux.execute(state)
    val state2 = state
    val result = state2.copy(circuit = state2.circuit.copy(modules = state.circuit.modules map {
      case mod: Module => onModule(mod)
      case ext: ExtModule => ext
    }))
    cleanupPasses.foldLeft(result) { case (s, xform) => xform.execute(s) }
  }
}
