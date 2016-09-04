package info.kwarc.mmt.metamath

import info.kwarc.mmt.api.objects.OMS
import info.kwarc.mmt.api.{NamespaceMap, Path}
import info.kwarc.mmt.api.DPath
import info.kwarc.mmt.api.utils.URI

object Metamath {
   val _base = DPath(URI.http colon "us.metamath.org")
   val setmm = _base ? "set.mm"
   val prelude = setmm.toDPath ? "Prelude"
   /*
   /** Application symbol for $d sets */
   val DV = OMS(prelude ? "DV")
   /** Application symbol for $e (essential) hypotheses */
   val ES = OMS(prelude ? "ES")
   /** Binding symbol for $f (floating) hypotheses. The statement
    *  
    * <pre>
    * $d x y z $.  $d z w $.
    * vx $f A x $.
    * vy $f B y $.
    * vz $f B z $.
    * thm.1 $e e1 x $.
    * thm.2 $e e2 y $.
    * thm $a stmt x z $.
    * </pre>
    * 
    * is translated to the term
    * 
    * <pre>
    * OMBINDC(FL,{x:A,y:B,z:B},OMA(ES,[e1,x],OMA(ES,[e2,y],[stmt,x,z])),
    *   OMA(DV,x,y,z),OMA(DV,z,w))
    * </pre>
    */
   val FL = OMS(prelude ? "FL")
   */
   def setconst(s: String) = OMS(setmm ? s)
   def prelconst(s : String) = prelude ? s
   
   val set = prelconst("set")
   val _class = prelconst("class")
   val wff = prelconst("wff")
   val |- = prelconst("|-")
   val wn = prelconst("wn")
   val wi = prelconst("wi")
   val wb = prelconst("wb")
   val wa = prelconst("wa")
   val wo = prelconst("wo")
}