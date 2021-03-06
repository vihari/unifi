/*
UniFi software.
Copyright [2001-2010] Sudheendra Hangal

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package unifi;


import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.bcel.classfile.Constant;
import org.apache.bcel.generic.*;

import unifi.df.BasicBlock;
import unifi.df.DF_state;
import unifi.drivers.Analyze;
import unifi.oo.FieldResolver;
import unifi.oo.MethodResolver;
import unifi.solver.Fraction;
import unifi.units.AllocUnit;
import unifi.units.ArrayLengthUnit;
import unifi.units.CheckcastUnit;
import unifi.units.ConstantUnit;
import unifi.units.FieldUnit;
import unifi.units.MethodParamUnit;
import unifi.units.MultUnit;
import unifi.units.PhiUnit;
import unifi.units.Unit;
import unifi.util.Util;

/** tracks state of unit elements on the stack
 at the entry/exit of a BB. has core unifi logic for every bytecode.
 A note on phi units: (sgh, aug 10, 2010)
 semantics of a phi unit: its a value at a stack slot at the beginning of an insn.
therefore a phi unit at the *result* of a bytecode like array load or iadd
 is associated with the *next* instruction. this difference is fairly subtle,
 and this simple program:
 class A {  private static void realignDaysOfWeek(String[] daysOfWeek) { int i = 0; daysOfWeek[i] = daysOfWeek[(i < 7) ? 0 : 1]; } }
took a while to get write because there are phi units with different types at a meet op which is also an aaload.
 */
public class unifi_state
    extends DF_state
{

private static Logger _logger = Logger.getLogger("unifi.unifi_state");
private static Logger bb_logger = Logger.getLogger("unifi.basicblocks");
private static Logger bytecode_logger = Logger.getLogger("unifi.bytecodes");
private static Logger unify_logger = Logger.getLogger("unifi.unify");
private static Logger method_logger = Logger.getLogger("unifi.methods");

private static MethodInvokeTracker methodDepTracker = new MethodInvokeTracker();

private static final boolean UNIFY_OBJECT_ARRAY_ELEMENTS = true; // set to true if we want object array elements to be unified

static { /* Runtime.getRuntime().traceMethodCalls(true); */ }

public Stack<Unit> _stack;

public unifi_state ()
{
    _stack = new Stack<Unit> ();
}

public void set_stack_height (int i)
{
    _stack.clear ();
    Util.ASSERT (_stack.size () == 0);

    for (int x = 0; x < i; x++)
    {
        _stack.push (null);
    }
}

public void clear ()
{
    int size = _stack.size ();
    set_stack_height (size);
    Util.ASSERT (_stack.size () == size);
}

public DF_state create_copy ()
{
    unifi_state other = new unifi_state ();
    // TODO: ensure that addAll will add elements in the same sequence
    other._stack.addAll (_stack);
    return other;
}

//private Stack get_stack() {  return _stack; }

public boolean equals (Object o)
{
    if (! (o instanceof unifi_state))
    {
        return false;
    }

    unifi_state s = (unifi_state) o;
    return (_stack.equals (s._stack));
}

public int hashCode () {
    int code = _stack.size();
    for (int i = 0 ; i < _stack.size() ; i++)
        code += _stack.peek().hashCode();
    return code;
}


/* updates current state with the meet of s.
   bb is the basic block at the entry to which
   this meet is occurring.
   in unifi_state, we don't need orig_bb_in_state.
 */
public void meet (DF_state s, BasicBlock bb, DF_state orig_bb_in_state)
{
    boolean second_of_two_words = false; // used only for phi nodes of longs/doubles
    // there is no meet at the entry of a catch block
    if (bb.is_catch_block_entry_point())
        return;

    if (bb_logger.isLoggable(Level.FINEST))
    {
        _logger.finest ("Computing meet at entrance to " + bb + " of \n" + this + "\nwith\n" + s);
    }
    Util.ASSERT (s instanceof unifi_state);
    Stack<Unit> other_stack = ( (unifi_state) s)._stack;
    int size = _stack.size ();
    if (size != other_stack.size ())
    {
        System.err.println ("size = " + size + ", others stack size = " + other_stack.size () + " Basic block " + bb);
        Util.ASSERT (false);
    }
    // unify each element on the 2 stacks -- in both directions
    for (int i = 0; i < size; i++)
    {
        if (second_of_two_words)
        {
            second_of_two_words = false;
            continue;
        }

        Unit b1 = _stack.elementAt (i);
        Unit b2 = (Unit) other_stack.elementAt (i);
        // check for null's
        if ( (b1 == null) && (b2 == null))
        {
            ;
        }
        //return false;
        else if ((b1 == null) && (b2 != null))
        {
            _stack.set (i, b2);
            // b2's type can sometimes be null, esp if we don't have debug information
            	if (Type.LONG.equals(b2.getType()) || Type.DOUBLE.equals(b2.getType()))
            	{
            		_stack.set(i+1, b2);
            		second_of_two_words = true;
            	}
        }
        else if ((b1 != null) && (b2 == null))
        {
            ;
        }
        else if (b1 == b2)
        {
        	; // do nothing if same unit on both sides of meet
        }
        else
        {
            // b1 != null && b2 != null
            // TODO: ASSERT that the types of b1 and b2 are the same
            if (unify_logger.isLoggable(Level.FINE)) { unify_logger.fine ("Unifying \n  " + b1 + "\n  " + b2); }
            // boolean b1_is_retval = (b1 instanceof ReturnValueUnit);
            // boolean b2_is_retval = (b2 instanceof ReturnValueUnit);

            // if (b1_is_retval || b2_is_retval)
            {
                // if second of two words, nothing to be done for the second word.
                if (!second_of_two_words)
                {
                    BCP bcp = new BCP (bb.get_mg(), bb.get_cpgen(), bb.get_begin_ih().getPosition(), true);
                    Unit phi_node = PhiUnit.get_phi_unit(bcp, i, b1.getType());
                    phi_node.unify (b1, bcp);
                    phi_node.unify (b2, bcp);
                    _stack.set(i, phi_node);
                	if (Type.LONG.equals(b1.getType()) || Type.DOUBLE.equals(b1.getType()))
                    {
                        // insert phi unit at the next slot as well.
                		if (b2.getType() != null)
                			Util.ASSERT (b2.getType().equals(b1.getType()));
                        _stack.set(i+1, phi_node);
                        second_of_two_words = true;
                    }
                }
            }
        }
    }
}

void set_is_magnitude (Unit u, boolean inside_hashcode_method)
{
    if (inside_hashcode_method) return;
    if (u == null) return;
    _logger.fine ("setting is_magnitude on " + u);
    u.unitAttribs().setQuantOrOrd();
}

void set_is_bit_encoded (Unit u, boolean inside_hashcode_method)
{
    if (inside_hashcode_method) return;
    if (u == null) return;
    _logger.fine ("setting is_bit_encoded on " + u);
    u.unitAttribs().setBitOpPerformed();
}

void set_is_rtt_checked (Unit u, boolean inside_hashcode_method)
{
    if (inside_hashcode_method) return;
    if (u == null) return;
    _logger.fine ("setting is_rtt_checked on " + u);
    u.unitAttribs().setDynamicTypeChecked();
}

void set_is_compared_for_equals (Unit u, boolean inside_hashcode_method)
{
    if (inside_hashcode_method) return;
    if (u == null) return;
    _logger.fine ("setting is_compared_for_equals on " + u);
    u.unitAttribs().setEqualityChecked();
}

private static final List<String> identity_math_funcs = Arrays.asList("abs","floor","round");
private static final List<String> dimensionless_math_funcs = Arrays.asList("acos","asin","atan", "cos","cosh","exp", "expm1", "log10", "log1p", "tan", "tanh", "toDegrees", "toRadians");
private static final List<String> identity_math_funcs_2_args = Arrays.asList("max","min","round");

// proper handling for java.lang.math semantics.
// notes: transcendentals should be marked dimensionless
// mu is needed because all mult units need to be registered in the method summary
// returns true if it did handle the method call.
private boolean handle_java_lang_math_funcs (String target_methname, String param_sig, Stack<Unit> st, MethodUnits mu)
{
    boolean handled = false;

    if (identity_math_funcs.contains(target_methname))
    {
        // do nothing
        handled = true;
    }
    else if ("sqrt".equals(target_methname) || "cbrt".equals(target_methname))
    {
        Unit u = st.pop(); st.pop();
        if (u != null)
        {
	        MultUnit root_u;
	        if ("sqrt".equals(target_methname))
	        	root_u = new MultUnit (u, u, new Fraction(-1,2));
	        else
	        	root_u = new MultUnit (u, u, new Fraction(-2,3));

	        Unit.registerUnit(root_u);
	        mu.getMethodSummary().addMultUnit(root_u);
	        st.push(root_u); st.push(root_u);
        }
        handled = true;
    }
    else if ("pow".equals(target_methname))
    {
        // temporarily use stack as a vector because ##!#!stack doesn't allow us to peek at more than then topmost element
        int stack_height = st.size();
        Unit exp = st.elementAt(stack_height-1);
        Unit base = st.elementAt(stack_height-3);

        // if exp is a constant.
        if (exp instanceof ConstantUnit)
        {
            ConstantUnit cunit = (ConstantUnit) exp;
            Object power = cunit.val();
            if (power instanceof Double)
            {
                double pow = ((Double) power).doubleValue();
                // try to find a match up to a denom of 10 (fractions with a higher denom are probably not interesting!)
                for (int denom = 1; denom <= 10; denom++)
                {
                    double exact_val = pow * denom;
                    long nearest_int = Math.round(exact_val);

                    if (Math.abs(exact_val - nearest_int) <= 0.01)
                    {
                        Fraction f = new Fraction(((int)nearest_int)-denom, denom);
                        MultUnit u = new MultUnit (base, base, f);
                        Unit.registerUnit(u);
                        mu.getMethodSummary().addMultUnit(u);

                        // pop the operands, push the result
                        st.pop(); st.pop(); st.pop(); st.pop();
                        st.push(u); st.push(u);
                        handled = true;
                        break;
                    }
                }
            }
        }
    }
    else if (identity_math_funcs_2_args.contains(target_methname))
    {
    	st.pop();
        if (param_sig.equals("(DD)D") || param_sig.equals("(JJ)J")) // some math_funcs with 2 args handle both longs and doubles e.g. max and min
            st.pop(); // pop one argument
        handled = true;
    }
    else if (dimensionless_math_funcs.contains(target_methname))
    {
    	Unit u = _stack.peek();
    	if (u != null)
    		u.set_is_dimension_less(true);
        handled = true;
    }
    /*
    else if ("atan2".equals(target_methname) ||
    {
        // compute (r,theta) from (x,y)
        // unify 2 tos arguments
        // pop one argument
        math_done = true;
    }
    else if ("atan2".equals(target_methname) ||
    {
        // compute (r,theta) from (x,y)
        // unify 2 tos arguments
        // pop one argument
        math_done = true;
    }
    else if ("random".equals(target_methname))
    {
        // push a null
    }
    else if ("IEEEremainder.equals(target_methname))
    {
        // just like mod:
        // tos = stack.pop();
        // tos_next = stack.pop();
        // mark_as_dimensionless(tos_next);
        // push (tos);
        math_done = true;
    }
*/
    return handled;
}

/** assert checks that u1 and u2 are the same object, or both are phi units.
 * this check is made when popping off longs and doubles from the stack. the invariant
 * for longs and doubles is that the unit for both words on the stack MUST be the same object,
 * except in the case where both are phi units created at a meet point
 * before we knew if the var was 2 words was 1.
 */
private void verifySameUnitsOrPhiUnits(Unit u1, Unit u2)
{
	if (u1 == null || u2 == null)
		return;
	Util.ASSERT (u1 == u2 || (u1 instanceof PhiUnit && u2 instanceof PhiUnit));
}

public void transfer (BasicBlock bb)
{
    Util.ASSERT (bb != null);
    if (bb_logger.isLoggable(Level.FINER)) { _logger.finer ("\nIn unifi transfer function for basic block " + bb); }
    bb.verify ();
    if (this != bb.get_in_state ())
    {
        if (bb_logger.isLoggable(Level.FINEST) )
        {
            bb_logger.finest ("this = " + this + "\nbb.get_in_state = " + bb.get_in_state () + "\nbb = " + bb);
            Util.ASSERT (false);
        }
    }
    Util.ASSERT (this == bb.get_in_state ());

    MethodGen mg = bb.get_mg ();
    String full_sig = mg.getClassName () + "." + mg.getName () + mg.getSignature ();
    ConstantPoolGen cpgen = bb.get_cpgen ();

    Stack<Unit> old_stack = ( (unifi_state) bb.get_in_state ())._stack;
    _stack = (Stack<Unit>) old_stack.clone();
    Util.ASSERT (_stack != null);
    Util.ASSERT (bb.get_begin_ih () != null);

    MethodUnits this_munits = unifi_DF_algorithm._current_munits;
    Util.ASSERT (this_munits != null);

// System.out.println ("stack depth at entry to bb pos " + bb.get_begin_ih().getPosition() + " is " + _stack.size());
    int stack_depth_tracker = _stack.size ();

    InstructionHandle ih = bb.get_begin_ih ();
    InstructionHandle handled_ih = null;

    // find out if we're inside a hashcode function - if so,
    // bit pattern ops will not cause the bit encoded attributes
    // to be set in the unit attributes.
    String method_sig = mg.getName () + mg.getSignature ();
    // compare is also similar to hashcode in that people do:
    // if (u1 != other.u1) return (u1 - other.u1);
    // if (u2 != other.u2) return (u2 - other.u2);
    // are there any others like this ?
    // we ignore body of toString methods because sometimes they return a string field
    // which unifies that field with return value of j.o.Object.toString()
    boolean inside_hashcode_method = method_sig.equals ("toString()Ljava/lang/String;") ||
    								 method_sig.equals ("hashCode()I") ||
                                     method_sig.equals ("compare(Ljava/lang/Object;Ljava/lang/Object;)I") ||
                                     method_sig.equals ("compareTo(Ljava/lang/Object;)I");

    do
    {
        Util.ASSERT (ih != null);

        if (stack_depth_tracker != _stack.size ())
        	Util.die ("FATAL ERROR: stack depth tracker expected size: " + stack_depth_tracker + " actual stack size: " + _stack.size());

        Instruction insn = ih.getInstruction ();
        if (bytecode_logger.isLoggable(Level.FINER)) {
//        	bytecode_logger.finer ("insn = " + Util.strip_package_from_class_name(insn.getClass().getName()) + ", pos " + ih.getPosition () + ", stackdepth " + stack_depth_tracker);
        	bytecode_logger.finer ("insn: " + ih + ", stackdepth " + stack_depth_tracker);
        }

        stack_depth_tracker -= insn.consumeStack (cpgen);
        Util.ASSERT (stack_depth_tracker >= 0);
        stack_depth_tracker += insn.produceStack (cpgen);

        // sanity check: disable assertion if it causes trouble
        for (Unit u: _stack)
        	if (u != null)
        		Util.ASSERT(Unit._current_unit_collection.contains(u));

        if (insn instanceof ConstantPushInstruction)
        {
            int n_stk_elements = insn.produceStack (cpgen);
            Util.ASSERT ( (n_stk_elements == 1) || (n_stk_elements == 2));

            for (int i = 0; i < n_stk_elements; i++)
            {
                _stack.push (null); //for constants push null
            }
        }
        else if (insn instanceof ConversionInstruction)
        {
            set_is_magnitude (_stack.peek(), inside_hashcode_method);

            if (insn instanceof I2L || insn instanceof I2D ||
                insn instanceof F2L || insn instanceof F2D)
            {
                // _stack.push ((Unit) _stack.peek ());

                Unit tos = _stack.pop();
                BCP bcp = new BCP (mg, cpgen, ih.getPosition());
                Type t = ((insn instanceof I2L) || (insn instanceof F2L)) ? Type.LONG : Type.DOUBLE;
                Unit u = CheckcastUnit.get_checkcast_unit (t, bcp);
               // Unit.registerUnit (u);
                u.unify (tos, bcp);
                _stack.push (u);
                _stack.push (u);
            }
            if (insn instanceof L2I || insn instanceof L2F ||
                insn instanceof D2I || insn instanceof D2F)
            {
                Unit tos = _stack.pop();
                Unit next = _stack.pop();
                Util.ASSERT (tos == next);

                BCP bcp = new BCP (mg, cpgen, ih.getPosition());
                Type t = ((insn instanceof L2I) || (insn instanceof D2I)) ? Type.INT : Type.FLOAT;
                Unit u = CheckcastUnit.get_checkcast_unit (t, bcp);
//                Unit.registerUnit (u);
                u.unify (tos, bcp);
                _stack.push(u);
            }
            if (insn instanceof I2F || insn instanceof F2I ||
                insn instanceof D2L || insn instanceof L2D)
            {
                Type t = null;

                // get target type first
                if (insn instanceof D2L)
                    t = Type.LONG;
                else if (insn instanceof L2D)
                    t = Type.DOUBLE;
                else if (insn instanceof F2I)
                    t = Type.INT;
                else if (insn instanceof I2F)
                    t = Type.FLOAT;

                Unit tos = _stack.pop();
                int size = t.getSize();
                Util.ASSERT (size == 1 || size == 2);
                if (size == 2)
                {
                    Unit next = _stack.pop();
                    Util.ASSERT (tos == next);
                }

                BCP bcp = new BCP (mg, cpgen, ih.getPosition());
                Unit u = CheckcastUnit.get_checkcast_unit (t, bcp);
                // now registering unit inside get_checkcast_unit(). no need to do it here.
                // Unit.registerUnit (u);
                u.unify (tos, bcp);
                _stack.push(u);
                if (size == 2)
                    _stack.push (u);
            }
        }
        else if (insn instanceof ACONST_NULL)
        {
            _stack.push (null); //for constants push null
        }
        else if (insn instanceof ARRAYLENGTH)
        {
            Unit arr_ref = _stack.pop ();
            // array ref may be null
            if (arr_ref != null)
                _stack.push (arr_ref.getLengthUnit ());
            else
                _stack.push (null);
        }
        else if (insn instanceof DUP)
        {
            _stack.push (_stack.peek ());
        }
        else if (insn instanceof DUP2)
        {
            Unit tos = _stack.pop ();
            Unit next_tos = _stack.pop ();
            _stack.push (next_tos);
            _stack.push (tos);
            _stack.push (next_tos);
            _stack.push (tos);
        }
        else if (insn instanceof LDC || insn instanceof LDC_W ||
                 insn instanceof LDC2_W)
        {
            CPInstruction cpi = (CPInstruction) insn;
            int index = cpi.getIndex();
            Constant c = cpgen.getConstant (index);
            Type t;
            if (c.getTag() == 7) // bcel doesn't handle tag7, Constant_class
            {
                t = Type.getType ("Ljava/lang/Class;");
            }
            else
                t = ((CPInstruction) insn).getType (cpgen);

            Object val;
            if (insn instanceof LDC)
                val = ((LDC) insn).getValue(cpgen);
            else
                val = ((LDC2_W) insn).getValue(cpgen);

            // get the const. unit for this BCP.
            // BCP objects are identical if their
            // components are identical, so creating a new BCP
            // should work.
            Unit u = ConstantUnit.get_constant_unit (t, new BCP(mg, cpgen, ih.getPosition()), val);

            // note: registration will be done by ConstantUnit.get_constant_unit, only if the unit is newly created,
            // so don't do it here.
            // Unit.registerUnit (u);

            _stack.push (u);
            if (insn instanceof LDC2_W)
            {
                _stack.push (u);
            }
        }
        else if (insn instanceof LocalVariableInstruction)
        {
            LocalVariableInstruction lvi = (LocalVariableInstruction) insn;
            int indx = lvi.getIndex (); //get the index of lv in the lvt
            indx = unifi_DF_algorithm._current_lv_map.pos_to_logical_LV_num (ih.getPosition ());
            Unit e = this_munits.get_local_var_unit (indx); //get its unit element

            // e could be null if we this_munits came from golden file
           // Util.ASSERT (e != null);

            if (insn instanceof IINC)
                set_is_magnitude(e, inside_hashcode_method);
            else if (insn instanceof LoadInstruction)
            {
                int n_stk_elements = insn.produceStack (cpgen);

                Util.ASSERT ( (n_stk_elements == 1) || (n_stk_elements == 2));
                // if this is a long/double, just double the unit's entry on the stack
                for (int i = 0; i < n_stk_elements; i++)
                {
                    _stack.push ( e);
                }
            }
            else
            {
                // insn is store, var is merged with tos
            	int n_stk_elements = insn.consumeStack (cpgen);
            	Util.ASSERT ( (n_stk_elements == 1) || (n_stk_elements == 2));

            	Unit tos = _stack.pop ();
            	if (e != null)
            		e.unify (tos, new BCP(mg, cpgen, ih.getPosition()));

            	if (n_stk_elements == 2)
            	{
            		Unit u = _stack.pop();
            		verifySameUnitsOrPhiUnits(tos, u);
            	}
            }
        }
        else if (insn instanceof InvokeInstruction)
        {
            InvokeInstruction ii = (InvokeInstruction) insn;
            String target_methname = ii.getMethodName (cpgen);
            String target_param_sig = ii.getSignature (cpgen);

            Type classType = ii.getReferenceType(cpgen);
            String className = "["; // if not object type, must be a call to array.clone()
            if (classType instanceof ObjectType)
            	className = ((ObjectType) classType).getClassName();

            boolean handled_by_math = false;

            if ("java.lang.Math".equals(className))
            {
                handled_by_math = handle_java_lang_math_funcs(target_methname, target_param_sig, _stack, this_munits);
                _logger.fine ("mathlib target_meth_name = " + target_methname + " target_meth_sig = " + target_param_sig + " handled = " + handled_by_math );
            }

            if (!handled_by_math && !className.startsWith ("[")) // must be array.clone (?)
            {

                // Note: if invokespecial to a constructor, it's just like a private
                // method call, we needn't look in any other class.
                // all private method calls are invokespecial, there isn't a invoke_nonvirtual bytecode
                // any more

                // inv special is used for 3 purposes: private calls, super calls (virtual), and constructors
                // private methods = invokespecial.
                // also invokespecial should not be a super call, i.e. class being called should be same as current class for a private call.
                String this_method_class_name = mg.getClassName();
                String sig = className + "." + target_methname + target_param_sig;

                boolean is_private = (insn instanceof INVOKESPECIAL) && this_method_class_name.equals(className);
                if (target_methname.equals("<init>"))
                    Util.ASSERT (insn instanceof INVOKESPECIAL);

            	MethodUnits target_munits = null;
            	boolean failed = false;
                try {
                	target_munits = MethodResolver.get_method_units (className, target_methname, target_param_sig, is_private, (insn instanceof INVOKESTATIC), false);
                } catch(RuntimeException e) {
                	_logger.severe("WARNING! classpath probably incorrect\n\n\n\nSkipping method call to: " + sig + ". Exception is: " + e);
                }

                if (target_munits == null || !Analyze.nameFilter.select (sig) || !Analyze.nameFilter.select(target_munits.full_sig()))
                {
                	// don't unify for called method, just adjust the stack
                	int consume = insn.consumeStack(cpgen);
                	int produce = insn.produceStack(cpgen);
                	for (int i = 0; i < consume; i++)
                		_stack.pop();
                	for (int i = 0; i < produce; i++)
                		_stack.push(null);
                	failed = true;
                	_logger.fine("No target_munits for " + className + "." + target_methname);
                }

                if (!failed)
                {
                	methodDepTracker.addMethodDep(this_munits, target_munits);
                	if (method_logger.isLoggable(Level.FINE))
                		method_logger.fine (sig + " maps to: " + target_munits.full_sig());
    				BCP bcp = new BCP(mg, cpgen, ih.getPosition());
    				MethodParamUnit[] params;

    				if (Analyze.CONTEXT_SENSITIVE_ANALYSIS)
    					params = target_munits.get_param_units_at (bcp, this_munits, target_munits);
    				else
    					params = target_munits.get_param_units();

    				// remember params is as long as # of words, not #
                	// of parameters. for doubles and longs, params will
                	// have duplicate MethodParamUnit's
                	// which we will harmless unify twice below - that's ok.

    				int pindex = params.length - 1;
                	for (; pindex >= 0; pindex--)
                	{
                		Unit u = _stack.pop();
            			if (_logger.isLoggable(Level.FINE))
            				unify_logger.fine ("Invoke point unifying " + params[pindex] + " and " + u);

            			// sgh: aug 10 2010
            			// we'll waive type checking in the case that:
            			// the method we are in implements an interface method, and therefore this_munits param[0][ ('this') is set to the interface, instead of the current class
            			// but 'this' is guaranteed to be this class or more specific.
            			// so we can check types with the real 'this' class instead of the interface class.

            			// this is a hack (and a similar one is used for returns below).
            			// it is based on trying to identify when param[0] (this) is used for a call or a return
            			// in reality, param[0] could be used in other places too (or be flowing through other vars, though unlikely in practice)
            			// so we should really fix the type of this_munits.param[0] to be the current class.
            			// however, this ran into problems (see comment at this_munits assignment at the start of this method, because changing type of this_munits.param[0]
            			// affects other methods that implement this interface method.
            			boolean waiveTypeCheck = false;

            			MethodParamUnit[] mpus = this_munits.get_param_units();
            			MethodParamUnit param_0 = null;
            			if (mpus != null && mpus.length > 0)
            				param_0 = mpus[0];
            			boolean is_u_param_0 = (u != null) && (u == param_0);

            			if (params[pindex] != null)
            			{
	            			boolean prerequisites = (insn instanceof INVOKEVIRTUAL) && (pindex == 0) && this_munits.is_interface_method() && is_u_param_0;
	            			if (prerequisites)
	            			{
	            				Type calleeThisType = params[pindex].getType();
	            				String sigOfThisClass = "L" + mg.getClassName() + ";";
	            				Type typeOnStack = Type.getType(sigOfThisClass);
	            	            ReferenceType r_calleeThisType = (ReferenceType) calleeThisType;
	            	            ReferenceType r_typeOnStack = (ReferenceType) typeOnStack;
	            	            try {
	            	            	if (r_typeOnStack.isCastableTo(r_calleeThisType))
	            	            	{
	                	            	waiveTypeCheck = true;
	                	            }
	            	            } catch (ClassNotFoundException cnfe) {
	            	                _logger.severe ("Unable to find class on classpath when checking cast-ability between types: " + r_typeOnStack + " and " + r_calleeThisType);
	            	            }

	            			}
	            			params[pindex].unify (u, bcp, waiveTypeCheck);
            			}
                	}

                	int n_stk_elements = insn.produceStack (cpgen);
                	Util.ASSERT ( (n_stk_elements >= 0) && (n_stk_elements <= 2));

                	for (int i = 0; i < n_stk_elements; i++)
                	{
                		if (Analyze.CONTEXT_SENSITIVE_ANALYSIS)
            				_stack.push (target_munits.get_return_value_unit_at (bcp, this_munits, target_munits));
            			else
            				_stack.push (target_munits.get_return_value_unit ());
                	}
                } // !failed
            }
        }
        else if (insn instanceof ReturnInstruction)
        {
            if (! (insn instanceof RETURN))
            {
                int n_stk_elements = insn.consumeStack (cpgen);
                Util.ASSERT ( (n_stk_elements == 1) || (n_stk_elements == 2));

                Unit tos_ue = _stack.peek ();
                Unit rv = this_munits.get_return_value_unit();
                BCP bcp = new BCP(mg, cpgen, ih.getPosition());

                // the type check waiver check is a hack - see comment above in InvokeInstruction
                // this is needed for the case when
                // interface I { R m(); }
                // class R1 extends R { R m() { return this; }
                // here 'this' is really type R1 (and therefore R), but since this_munits refers to I.m() its param[0] has type I, which is not assignable to R
                boolean waiveTypeCheck = false;

    			MethodParamUnit[] mpus = this_munits.get_param_units();
    			MethodParamUnit param_0 = null;
    			if (mpus != null && mpus.length > 0)
    				param_0 = mpus[0];
    			boolean is_param_0 = (tos_ue != null) && (tos_ue == param_0);
    			boolean prerequisites = this_munits.is_interface_method() && is_param_0;

    			if (prerequisites && rv != null)
    			{
    				Type rvType = rv.getType();
    				String sigOfThisClass = "L" + mg.getClassName() + ";";
    				Type typeOnStack = Type.getType(sigOfThisClass);
    	            ReferenceType r_rvType = (ReferenceType) rvType;
    	            ReferenceType r_typeOnStack = (ReferenceType) typeOnStack;
    	            try {
    	            	if (r_typeOnStack.isCastableTo(r_rvType))
    	            	{
        	            	waiveTypeCheck = true;
        	            }
    	            } catch (ClassNotFoundException cnfe) {
    	                _logger.severe ("Unable to find class on classpath when checking cast-ability between types: " + r_typeOnStack + " and " + r_rvType);
    	            }
    			}

    			if (rv != null)
    				rv.unify (tos_ue, bcp, waiveTypeCheck);

                for (int i = 0; i < n_stk_elements; i++)
                {
                    _stack.pop ();
                  //  Util.ASSERT (ue == tos_ue);
                }
            }

            // weird, in eclipse jar ch.epfl.lamp.sdt.aspects_2.7.7.final.jar
            // method scala.tools.eclipse.contribution.weaving.jdt.builderoptions.ScalaJavaBuilderAspect.ajc$around$scala_tools_eclipse_contribution_weaving_jdt_builderoptions_ScalaJavaBuilderAspect$1$d87ca07cproceed(Lorg/eclipse/jdt/internal/core/builder/BatchImageBuilder;ZLorg/aspectj/runtime/internal/AroundClosure;)V(static)
            // does leave elements on the stack when it returns
      //     if (!_stack.isEmpty ()); // no stack elements shd remain ...
      //     	_logger.severe("Stack not empty at return!");

            Util.ASSERT (ih == bb.get_end_ih ());
        }
        else if (insn instanceof IADD || insn instanceof ISUB ||
                 insn instanceof DADD || insn instanceof DSUB ||
                 insn instanceof FADD || insn instanceof FSUB ||
                 insn instanceof LADD || insn instanceof LSUB ||
                 insn instanceof IREM || insn instanceof FREM ||
                 insn instanceof LREM || insn instanceof DREM ||
                 insn instanceof IAND || insn instanceof LAND ||
                 insn instanceof IOR  || insn instanceof LOR ||
                 insn instanceof IXOR || insn instanceof LXOR)
        {
            int n_consume_stk = insn.consumeStack (cpgen);
            int n_produce_stk = insn.produceStack (cpgen);
            Util.ASSERT (n_consume_stk == 2 * n_produce_stk);

            Unit op1 = _stack.pop ();
            if (n_produce_stk == 2)
            {
                Unit u = _stack.pop ();
                verifySameUnitsOrPhiUnits(op1, u);
            }

            Unit op2 = _stack.pop ();
            if (n_produce_stk == 2)
            {
                Unit u = _stack.pop ();
                verifySameUnitsOrPhiUnits(op2, u);
            }


            if (insn instanceof IADD || insn instanceof ISUB ||
                insn instanceof DADD || insn instanceof DSUB ||
                insn instanceof FADD || insn instanceof FSUB ||
                insn instanceof LADD || insn instanceof LSUB ||
                insn instanceof IREM || insn instanceof FREM ||
                insn instanceof LREM || insn instanceof DREM)
 	    {
	        set_is_magnitude (op1, inside_hashcode_method); // arithmetic insn
	        set_is_magnitude (op2, inside_hashcode_method); // arithmetic insn
	    }
	    else
            {
	        set_is_bit_encoded (op1, inside_hashcode_method); // logical insn
	        set_is_bit_encoded (op2, inside_hashcode_method); // logical insn
            }

            // for simple add/sub, merge the dvars of the two operands
            // and push it back onto the stack.
            if (insn instanceof IADD || insn instanceof ISUB ||
                insn instanceof DADD || insn instanceof DSUB ||
                insn instanceof FADD || insn instanceof FSUB ||
                insn instanceof LADD || insn instanceof LSUB)
            {
                if (op1 != null)
                {
                    // op1.unify (op2, new BCP(mg, cpgen, ih.getPosition()));
                    if (op2 != null)
                    {
                        // create a phi unit which both op1 and op2 feed into.
                    	// we do this just for maintaining directionality (for the field sensitive analysis)
                    	// but its not needed for a pure unification based analysis
                        BCP bcp = new BCP(mg, cpgen, ih.getPosition() + ih.getInstruction().getLength());
                        Type t = op1.getType();
                        if (t == null)
                            t = op2.getType();

                        // note: same phi unit will be pushed on stack twice for longs and doubles.
                        // important: the slot # is important for the phi unit because the stack may not
                        // be empty, so we can't assume this operation is for slot 0 (used to be a bug)
                        int slot = _stack.size();
                        PhiUnit p = PhiUnit.get_phi_unit(bcp, slot, t);
                        p.unify (op1, bcp);
                        p.unify(op2, bcp);
                        op1 = p;
                    }
                }
                else
                {
                    op1 = op2; // if op1 = null, what needs to be pushed is op2,
                    // regardless of whether op2 is null or not
                }
            }

            // for remainder, op2 is considered dimensionless, dvar of
            // result is same as dvar of op1
            if (insn instanceof IREM || insn instanceof FREM ||
                insn instanceof LREM || insn instanceof DREM)
            {
                // 2nd arg to a remainder op is dimensionless
                if (op2 != null)
                    op2.set_is_dimension_less(true);
            }

            for (int i = 0; i < n_produce_stk; i++)
            {
                _stack.push (op1);
            }
        }
        else if (insn instanceof IMUL || insn instanceof IDIV ||
                 insn instanceof DMUL || insn instanceof DDIV ||
                 insn instanceof FMUL || insn instanceof FDIV ||
                 insn instanceof LMUL || insn instanceof LDIV)
        {
            Fraction coeff = (insn instanceof IMUL || insn instanceof LMUL ||
                            insn instanceof FMUL || insn instanceof DMUL)
                            ? new Fraction(1,1) : new Fraction(-1,1);
            int n_words = (insn instanceof IMUL || insn instanceof IDIV ||
                           insn instanceof FMUL || insn instanceof FDIV)
                         ? 1 : 2;

            Unit u1 = _stack.pop();
            set_is_magnitude (u1, inside_hashcode_method); // arithmetic insn
            for (int i = 1; i < n_words; i++)
                _stack.pop();
            Unit u2 = _stack.pop();
            set_is_magnitude (u2, inside_hashcode_method); // arithmetic insn
            for (int i = 1; i < n_words; i++)
                _stack.pop();

            Unit result_unit;

            if ((u1 == null) || (u2 == null))
                result_unit = null;
            else
            {
                result_unit = new MultUnit (u2, u1, coeff);
                Unit.registerUnit(result_unit);
                this_munits.getMethodSummary().addMultUnit((MultUnit) result_unit);
                set_is_magnitude (result_unit, inside_hashcode_method);
            }

            for (int i = 0; i < insn.produceStack (cpgen); i++)
                _stack.push (result_unit);
        }
        else if (insn instanceof FieldInstruction)
        {
            boolean is_static = (insn instanceof GETSTATIC) || (insn instanceof PUTSTATIC);
            if (insn instanceof GETFIELD || insn instanceof GETSTATIC)
            {
                FieldInstruction fi = (FieldInstruction) insn;
                int elements = insn.produceStack (cpgen);
                Util.ASSERT (elements == 1 || elements == 2);
                if (!is_static)
                {
                    _stack.pop ();
                }
                FieldUnit fue = FieldResolver.getFieldUnit (fi.getFieldType (cpgen), ((ObjectType) fi.getReferenceType(cpgen)).getClassName(), fi.getFieldName(cpgen), is_static, true);
                for (int i = 0; i < elements; i++)
                {
                    _stack.push (fue);
                }
            }
            else if (insn instanceof PUTFIELD || insn instanceof PUTSTATIC)
            {
                FieldInstruction fi = (FieldInstruction) insn;
                Type t = fi.getFieldType (cpgen);

                Unit tos = _stack.pop ();

                if ( (t == Type.DOUBLE) || (t == Type.LONG))
                {
                    Unit next_tos = _stack.pop ();
                    verifySameUnitsOrPhiUnits(tos, next_tos);
                }

                if (!is_static)
                {
                    _stack.pop (); // for the obj ref

                }
                FieldUnit fue = FieldResolver.getFieldUnit (t, ((ObjectType) fi.getReferenceType(cpgen)).getClassName(), fi.getFieldName(cpgen), is_static, true);
                if (fue != null)
                	fue.unify (tos, new BCP(mg, cpgen, ih.getPosition()));
            }
        }
        else if (insn instanceof NEW)
        {
            CPInstruction cpi = (CPInstruction) insn;
            Type t = cpi.getType (cpgen);
            BCP bcp = new BCP (mg, cpgen, ih.getPosition());
            AllocUnit existing_u = AllocUnit.globalAllocUnitDir.get (bcp);
            if (existing_u == null)
            {
                AllocUnit u = new AllocUnit (t, -1, full_sig, bcp);
                AllocUnit.globalAllocUnitDir.put (bcp, u);
                Unit.registerUnit (u);
                existing_u = u;
            }
            _stack.push (existing_u);
        }
        else if (insn instanceof AllocationInstruction)
        {
            BCP bcp = new BCP (mg, cpgen, ih.getPosition());

            if (insn instanceof NEWARRAY || insn instanceof ANEWARRAY)
            {
                ArrayType arr_t;
                Type base_t;

                if (insn instanceof NEWARRAY)
                {
                    arr_t = (ArrayType) ( (NEWARRAY) insn).getType ();
                    base_t = arr_t.getElementType ();
                }
                else
                {
                    // note the getType returns different things for
                    // newarray v/s anewarray
                    base_t = ( (ANEWARRAY) insn).getType (cpgen);
                    arr_t = new ArrayType (base_t, 1);
                }

                Unit array_base_unit, array_length_unit;
                AllocUnit array_unit;

                // if allocunit has been already allocated for this BCP
                // we won't do it again. this can happen when same basic blk
                // is re-analyzed by the dataflow algorithm
                // for arrays, bcp_to_alloc_unit_map contains
                // the bcp -> array_unit mapping.
                array_unit = AllocUnit.globalAllocUnitDir.get (bcp);
                if (array_unit == null)
                {
                    array_base_unit = new AllocUnit (base_t, -1, full_sig, new BCP (mg, cpgen, ih.getPosition()));
                    Unit.registerUnit (array_base_unit);

                    array_unit = new AllocUnit (arr_t, 0, full_sig, new BCP (mg, cpgen, ih.getPosition()));
                    Unit.registerUnit (array_unit);

                    array_unit.setArrayOf (array_base_unit);
                    array_base_unit.setElementOf (array_unit);

                    AllocUnit.globalAllocUnitDir.put (bcp, array_unit);

                    array_length_unit = new ArrayLengthUnit (array_unit);
                    Unit.registerUnit (array_length_unit);
                }
                else
                {
                    array_length_unit = array_unit.getLengthUnit();
                }

                Util.ASSERT (array_length_unit != null);

                Unit size_ue = _stack.pop ();
                array_length_unit.unify (size_ue, bcp);
                _stack.push (array_unit);
            }
            else if (insn instanceof MULTIANEWARRAY)
            {
                ArrayType t = (ArrayType) ( (MULTIANEWARRAY) insn).getType (cpgen);
                int dimensions_on_stack = ( (MULTIANEWARRAY) insn).getDimensions ();
                int total_dimensions = 1 + t.getSignature().lastIndexOf('[');
                Util.ASSERT (total_dimensions >= dimensions_on_stack);
                Util.ASSERT (dimensions_on_stack > 0);
                Util.ASSERT (total_dimensions > 1); // if only 1 dimension, {a}newarray should have been used

                bytecode_logger.warning ("Multianewarray, #dimensions " + total_dimensions + ", on stack " + dimensions_on_stack);

                System.err.println("\nWARNING!!! WARNING!!! multianewarray called\n");

                Type element_type = t.getBasicType ();
                AllocUnit u = null, prev_u = new AllocUnit(element_type, -1, full_sig, new BCP (mg, cpgen, ih.getPosition ()));
                Unit.registerUnit (prev_u);
                prev_u.verify ();

                for (int i = 0; i < total_dimensions; i++)
                {
                    element_type = new ArrayType (element_type, 1);
                    u = new AllocUnit (element_type, i, full_sig, new BCP (mg, cpgen, ih.getPosition ()));
                    Unit.registerUnit (u);
                    ArrayLengthUnit length_unit = new ArrayLengthUnit(u);
                    Unit.registerUnit (length_unit);

                    u.setArrayOf (prev_u);
                    prev_u.setElementOf (u);

                    if (i >= total_dimensions-dimensions_on_stack)
                    {
                        Unit size = _stack.pop ();
                        length_unit.unify (size, new BCP(mg, cpgen, ih.getPosition()));
                    }

                    prev_u = u;
                }
                // push the unit for the final array ref
                _stack.push (u);
            }
            else
            {
                Util.ASSERT (false);
            }
        }
        else if (insn instanceof ArrayInstruction)
        {

            // unify index with array's length_ue
            // for store's unify element with array's base type
            if (insn instanceof StackProducer)
            {
                int words = insn.produceStack (cpgen);
                Util.ASSERT ( (words == 1) || (words == 2));

                Unit index = _stack.pop ();
                Unit ref = _stack.pop ();

                BCP bcp = new BCP(mg, cpgen, ih.getPosition() + ih.getInstruction().getLength());
                // important: the slot # is important for the phi unit because the stack may not
                // be empty, so we can't assume this operation is for slot 0 (used to be a bug)
                int slot = _stack.size() + 1;

                PhiUnit p_index = PhiUnit.get_phi_unit(bcp, slot, Type.INT);

                p_index.unify (index, bcp);

                // unify lengths, regardless of object vs. primitive type
                if (ref != null)
                	p_index.unify(ref.getLengthUnit(), bcp);

                /*
                // note: unifi in both directions. used to be a bug.
                if (ref != null)
                    ref.get_length_ue ().unify (index, new BCP(mg, cpgen, ih.getPosition()));
                if (index != null)
                    index.unify (ref.get_length_ue(), new BCP(mg, cpgen, ih.getPosition()));
                */

                // we don't want to unify elements for object arrays, so we just push null's
                // but we want to unify for primitive type arrays
                Unit ue;
                if (insn instanceof AALOAD && !UNIFY_OBJECT_ARRAY_ELEMENTS)
                	ue = null;
                else
                	ue = (ref != null) ? (Unit) ref.getArrayOf () : null;

                _stack.push (ue);
                if (words == 2)
                {
                    _stack.push (ue);
                }
            }
            else //StackConsumer
            {
                int words = insn.consumeStack (cpgen);
                Util.ASSERT (words == 3 || words == 4);

                Unit value = _stack.pop ();
                if (words == 4)
                {
                    _stack.pop (); // this should return value also
                }
                Unit index = _stack.pop ();
                Unit ref = _stack.pop ();

                if ((ref != null) && (index != null))
                {
                    BCP bcp = new BCP(mg, cpgen, ih.getPosition() + ih.getInstruction().getLength());
                    // important: the slot # is important for the phi unit because the stack may not
                    // be empty, so we can't assume this operation is for slot 0 (used to be a bug)
                    int slot = _stack.size() + 1; // remaining elements on stack + 1 for index

                    PhiUnit p_index = PhiUnit.get_phi_unit(bcp, slot, Type.INT);

                    // we don't want to unify elements for object arrays, but want to do so for primitive type arrays
                    p_index.unify (index, bcp);
                    p_index.unify(ref.getLengthUnit(), bcp);
                }

                // ref may be null - this happens when
                // going thru the bb the first time for the transfer function
                // the stack is not really set up at this time,
                // so the units of the stack elements are not there
                if (ref != null)
                {
                    // ref.get_length_ue ().unify (index, new BCP(mg, cpgen, ih.getPosition()));

                	// we don't want to unify elements for object arrays, but want to do so for primitive type arrays
                	if (!(insn instanceof AASTORE && !UNIFY_OBJECT_ARRAY_ELEMENTS))
                		ref.getArrayOf().unify (value, new BCP(mg, cpgen, ih.getPosition()));
                }
            }
        }
        else if (insn instanceof GotoInstruction)
        {
            Util.ASSERT (ih == bb.get_end_ih ());
        }
        else if (insn instanceof IfInstruction || insn instanceof Select)
        {
            if (insn instanceof IFEQ || insn instanceof IFNE)
 	    {
                Unit first = _stack.pop ();
                set_is_compared_for_equals(first, inside_hashcode_method);
            }
            else if ((insn instanceof IFLE || insn instanceof IFGE) ||
                     (insn instanceof IFGT || insn instanceof IFLT))
            {
                Unit first = _stack.pop ();
                set_is_magnitude(first, inside_hashcode_method);
            }
            else if (insn instanceof IFNONNULL || insn instanceof IFNULL)
            {
                _stack.pop ();
            }
            else if (insn instanceof Select)
            {
                Unit u = _stack.pop (); // this is for tableswitch/lookupswitch
                if (insn instanceof TABLESWITCH)
		    set_is_compared_for_equals(u, inside_hashcode_method);
                else
                {
                    Util.ASSERT (insn instanceof LOOKUPSWITCH);
		    set_is_magnitude(u, inside_hashcode_method);
                }
            }
            else
            {
                Unit first = _stack.pop ();
                Unit second = _stack.pop ();
                if ((insn instanceof IF_ICMPEQ) || (insn instanceof IF_ICMPNE))
                {
                    set_is_compared_for_equals (first, inside_hashcode_method);
                    set_is_compared_for_equals (second, inside_hashcode_method);
                }
                else if ((insn instanceof IF_ICMPLT) || (insn instanceof IF_ICMPLE) ||
                         (insn instanceof IF_ICMPGE) || (insn instanceof IF_ICMPGT))
                {
                    _logger.fine ("setting as magnitude: " + first);
                    _logger.fine ("setting as magnitude: " + second);
                    set_is_magnitude (first, inside_hashcode_method);
                    set_is_magnitude (second, inside_hashcode_method);
                }

                // need a phi unit here because no directionality is associated with a compare
                BCP bcp = new BCP(mg, cpgen, ih.getPosition() + ih.getInstruction().getLength());
                Type t = (first != null) ? first.getType() : null;
                if (t == null)
                    t = (second != null) ? second.getType() : null;
                int slot = _stack.size();
                PhiUnit p = PhiUnit.get_phi_unit(bcp, slot, t);
                p.unify (first, bcp);
                p.unify(second, bcp);

                /*
                if (first != null)
                {
                    first.unify (second, new BCP(mg, cpgen, ih.getPosition()));
                }
                // unify in both directions, used to be a bug.
                if (second != null)
                {
                    second.unify (first, new BCP(mg, cpgen, ih.getPosition()));
                }
                */
            }

            Util.ASSERT (ih == bb.get_end_ih ());
        }
        else if (insn instanceof CHECKCAST)
        {
            Unit tos = _stack.pop();
            BCP bcp = new BCP (mg, cpgen, ih.getPosition());
            Type checkcastResult = ((CHECKCAST) insn).getType(cpgen);
            Unit u = null;
            Type objectType = Type.getType(Object.class);
            Type stringType = Type.getType(String.class);

            Type tosBasicType = null;
            if (tos != null)
            {
            	Type tosType = tos.getType();
            	tosBasicType = (tosType instanceof ArrayType) ? ((ArrayType) tosType).getBasicType() : tosType;
            }

            boolean castOfObjectToString = tos != null;
            // if one of these are null, consider it as cast of object's to string
            if (tosBasicType==null || checkcastResult == null) {
                castOfObjectToString = true;
            } else {
                castOfObjectToString = castOfObjectToString && tosBasicType.equals(objectType) && checkcastResult.equals(stringType);
            }
            // we deliberately ignore casts of object's to string... cause too much noise
            if (tos != null && !castOfObjectToString)
            {
            	u = CheckcastUnit.get_checkcast_unit (checkcastResult, bcp);
            	u.unify (tos, bcp);
            }
            else
            	if (tos != null)
            		_logger.warning("FLAG DEBUG: ignored cast of " + tos.getType()  + " to string");

            _stack.push (u);
        }
        else if (insn instanceof INSTANCEOF)
        {
            Unit u = _stack.pop ();
	    set_is_rtt_checked (u, inside_hashcode_method);
            _stack.push (null); // result is 0 or 1
        }
        else if (insn instanceof ATHROW)
        {
            /* do nothing */
            Util.ASSERT (ih == bb.get_end_ih ());
        }
        else if ((insn instanceof LCMP) || (insn instanceof DCMPG) || (insn instanceof DCMPL))
        {
        	Unit tmp_first = _stack.pop ();
        	Unit first = _stack.pop ();
        	Util.ASSERT (tmp_first == first);
        	Unit tmp_second = _stack.pop ();
        	Unit second = _stack.pop ();
        	Util.ASSERT (tmp_second == second);

        	set_is_magnitude (first, inside_hashcode_method);
        	set_is_magnitude (second, inside_hashcode_method);

        	BCP bcp = new BCP(mg, cpgen, ih.getPosition() + ih.getInstruction().getLength());
        	Type t = (first != null) ? first.getType() : null;
        	if (t == null)
        		t = (second != null) ? second.getType() : null;
        		int slot = _stack.size();

        		PhiUnit p = PhiUnit.get_phi_unit(bcp, slot, t);
        		p.unify (first, bcp);
        		p.unify (second, bcp);

        		/*
            if (first != null)
            {
                first.unify (second, new BCP(mg, cpgen, ih.getPosition()));
            }
        		 */
        		_stack.push (null);
        }
        else if ((insn instanceof FCMPG) || (insn instanceof FCMPL))
        {
            Unit first = _stack.pop ();
            Unit second = _stack.pop ();

   	    set_is_magnitude (first, inside_hashcode_method);
   	    set_is_magnitude (second, inside_hashcode_method);

            BCP bcp = new BCP(mg, cpgen, ih.getPosition() + ih.getInstruction().getLength());
            Type t = (first != null) ? first.getType() : null;
            if (t == null)
                t = (second != null) ? second.getType() : null;
            int slot = _stack.size();

            PhiUnit p = PhiUnit.get_phi_unit(bcp, slot, t);
            p.unify (first, bcp);
            p.unify(second, bcp);

            /*
            if (first != null)
            {
                first.unify (second, new BCP(mg, cpgen, ih.getPosition()));

            }
            */
            _stack.push (null);
        }
        else if (insn instanceof POP)
        {
            _stack.pop ();
        }
        else if (insn instanceof POP2)
        {
            _stack.pop ();
            _stack.pop ();
        }
        else if (insn instanceof SWAP)
        {
            Unit o1 = _stack.pop ();
            Unit o2 = _stack.pop ();
            _stack.push (o2);
            _stack.push (o1);
        }
        else if (insn instanceof DUP_X1)
        {
        	Unit o1 = _stack.pop ();
        	Unit o2 = _stack.pop ();
            _stack.push (o1);
            _stack.push (o2);
            _stack.push (o1);
        }
        else if (insn instanceof DUP_X2)
        {
        	Unit o1 = _stack.pop ();
        	Unit o2 = _stack.pop ();
        	Unit o3 = _stack.pop ();
            _stack.push (o1);
            _stack.push (o3);
            _stack.push (o2);
            _stack.push (o1);
        }
        else if (insn instanceof DUP2_X1)
        {
        	Unit o1 = _stack.pop ();
        	Unit o2 = _stack.pop ();
        	Unit o3 = _stack.pop ();
            _stack.push (o2);
            _stack.push (o1);
            _stack.push (o3);
            _stack.push (o2);
            _stack.push (o1);
        }
        else if (insn instanceof DUP2_X2)
        {
        	Unit o1 = _stack.pop ();
        	Unit o2 = _stack.pop ();
        	Unit o3 = _stack.pop ();
        	Unit o4 = _stack.pop ();
            _stack.push (o2);
            _stack.push (o1);
            _stack.push (o4);
            _stack.push (o3);
            _stack.push (o2);
            _stack.push (o1);
        }
        else if ((insn instanceof INEG) ||
                 (insn instanceof LNEG) ||
                 (insn instanceof DNEG) ||
                 (insn instanceof FNEG))
        {
   	    set_is_magnitude (_stack.peek(), inside_hashcode_method);
        }
        else if (insn instanceof NOP)
        {
            // do nothing
        }
        else if (insn instanceof JsrInstruction)
        {
            _stack.push (null); // the PC gets pushed; it has no units
        }
        else if (insn instanceof RET)
        {
           // do nothing - note RET doesn't pop from stack, it
           // gets the return address from a local var
        }
        else if ((insn instanceof LSHL) || (insn instanceof LSHR) || (insn instanceof LUSHR))
        {
            // long unsigned shift right
            _stack.pop();
            Unit o1 = _stack.pop();
            Unit o2 = _stack.pop();
            Util.ASSERT (o1 == o2);
             _stack.push (o2);
            _stack.push (o1);
        }
        else if ((insn instanceof ISHL) || (insn instanceof ISHR) || (insn instanceof IUSHR))
        {
            // int shift's - shift amount is top of stack, pop it
            Unit u = _stack.pop();
            set_is_bit_encoded (u, inside_hashcode_method);
        }
        else if ((insn instanceof MONITORENTER) || (insn instanceof MONITOREXIT))
        {
            // get rid of the obj ref on tos -
            // is it worthwhile attaching attributes to this unit
            // saying it's a object which can be locked
            _stack.pop();
        }
        else
            Util.ASSERT (false, "Unhandled instruction");

        handled_ih = ih;
        ih = ih.getNext ();

    }
    while (handled_ih != bb.get_end_ih ());

    ( (unifi_state) bb.get_out_state ())._stack = _stack;
    ( (unifi_state) bb.get_in_state ())._stack = old_stack;
    //return false;
}

public void verify_against_old_state(DF_state state, BasicBlock bb) { /* */ }

public String toString ()
{

    return ("Unifi state with stack size " + _stack.size ());
}

public static MethodInvokeTracker getMethodDepTracker() {
	return methodDepTracker;
}
}
