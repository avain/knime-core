/* @(#)$RCSfile$ 
 * $Revision$ $Date$ $Author$
 * 
 * -------------------------------------------------------------------
 * This source code, its documentation and all appendant files
 * are protected by copyright law. All rights reserved.
 * 
 * Copyright, 2003 - 2006
 * Universitaet Konstanz, Germany.
 * Lehrstuhl fuer Angewandte Informatik
 * Prof. Dr. Michael R. Berthold
 * 
 * You may not modify, publish, transmit, transfer or sell, reproduce,
 * create derivative works from, distribute, perform, display, or in
 * any way exploit any of the content, in whole or in part, except as
 * otherwise expressly permitted in writing by the copyright owner.
 * -------------------------------------------------------------------
 * 
 * History
 *   23.03.2006 (cebron): created
 */
package de.unikn.knime.core.data;

/**
 * Interface supporting generic complex number values.
 * 
 * @author ciobaca, University of Konstanz
 */
public interface ComplexNumberValue extends DataValue {
    
    /**
     * @return The real part of the complex number
     */
    double getRealValue();

    /**
     * @return The imaginary part of the complex number
     */
    double getImaginaryValue();
}
