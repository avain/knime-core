/*
 * ------------------------------------------------------------------------
 *
 *  Copyright (C) 2003 - 2010
 *  University of Konstanz, Germany and
 *  KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * --------------------------------------------------------------------- *
 *
 * History
 *   17.01.2006(sieb, ohl): reviewed
 */
package org.knime.core.node;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import org.knime.core.data.DataTableSpec;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.property.hilite.HiLiteHandler;
import org.knime.core.node.workflow.FlowLoopContext;
import org.knime.core.node.workflow.FlowObjectStack;
import org.knime.core.node.workflow.FlowVariable;
import org.knime.core.node.workflow.LoopEndNode;
import org.knime.core.node.workflow.LoopStartNode;


/**
 * Abstract class defining a node's configuration and execution (among others).
 * More specifically, it defines:
 * <ul>
 * <li>Input and output ports (count and types)</li>
 * <li>Settings handling (validation and storage)</li>
 * <li>Configuration (e.g. after new settings are applied or a node
 * is (re)connected</li>
 * <li>Execution</li>
 * <li>Reset</li>
 * <li>Storage of &quot;internals&quot; (e.g. hilite translation and/or
 * information that is shown in node view)
 * </ul>
 * Derived classes must overwrite one of two execute methods and
 * one of two configure methods (depending on their port types):
 * <ol>
 * <li>The {@link #execute(PortObject[], ExecutionContext)} and
 * {@link #configure(PortObjectSpec[])} methods for general
 * port definitions (rarely used) or
 * <li>the {@link #execute(BufferedDataTable[], ExecutionContext)} and
 * {@link #configure(DataTableSpec[])} methods for standard data ports
 * (on both in- and outports).
 * </ol>
 * None of these methods is declared abstract, though one pair of
 * execute/configure must be overridden (if none is overwritten a runtime
 * exception will be thrown upon the node's configuration or execution, resp.).
 *
 * <p>
 * For a detailed description of this class refer to KNIME's extension guide
 * and the various node implementations.
 *
 * @author Thomas Gabriel, University of Konstanz
 */
public abstract class NodeModel {

    /**
     * The node logger for this class; do not make static to make sure the right
     * class name is printed in messages.
     */
    private final NodeLogger m_logger;

    /** Hold in and output port types. */
    private final PortType[] m_inPortTypes;
    private final PortType[] m_outPortTypes;

    /** Holds the input hilite handler for each input. */
    private final HiLiteHandler[] m_inHiLiteHdls;

    /** Hilite adapter returned in
     * {@link NodeModel#getInHiLiteHandler(int)} when the current in-port
     * hilite handler is <code>null</code>, e.g. the node is not fully
     * connected.
     */
    private static final HiLiteHandler HILITE_ADAPTER = new HiLiteHandler();

    /** Keeps a list of registered views. */
    private final CopyOnWriteArrayList<AbstractNodeView<?>> m_views;

    /** Flag for the hasContent state. */
    private boolean m_hasContent;

    /**
     * Optional warning message to be set during / after execution. Enables
     * higher levels to display the given message.
     */
    private String m_warningMessage = null;

    /** The listeners that are interested in changes of the model warning. */
    private final CopyOnWriteArraySet<NodeModelWarningListener>
                                                         m_warningListeners;

    /**
     * Creates a new model with the given number of input and
     * output data ports.
     * @param nrInDataPorts number of input data ports
     * @param nrOutDataPorts number of output data ports
     * @throws NegativeArraySizeException If the number of in- or outputs is
     *             smaller than zero.
     */
    protected NodeModel(final int nrInDataPorts,
            final int nrOutDataPorts) {
        this(createPOs(nrInDataPorts), createPOs(nrOutDataPorts));
    }

    private static PortType[] createPOs(final int nrDataPorts) {
        PortType[] portTypes = new PortType[nrDataPorts];
        Arrays.fill(portTypes, BufferedDataTable.TYPE);
        return portTypes;
    }

    /**
     * Creates a new model with the given number (and types!) of input and
     * output types.
     * @param inPortTypes an array of non-null in-port types
     * @param outPortTypes an array of non-null out-port types
     */
    protected NodeModel(final PortType[] inPortTypes,
            final PortType[] outPortTypes) {
        // create logger
        m_logger = NodeLogger.getLogger(this.getClass());

        // init message listener array
        m_warningListeners =
                       new CopyOnWriteArraySet<NodeModelWarningListener>();

        // check port types of validity and store them
        if (inPortTypes == null) {
            m_inPortTypes = new PortType[0];
        } else {
            m_inPortTypes = new PortType[inPortTypes.length];
        }
        for (int i = 0; i < inPortTypes.length; i++) {
            if (inPortTypes[i] == null) {
                throw new NullPointerException("InPortType[" + i
                        + "] must not be null!");
            }
            m_inPortTypes[i] = inPortTypes[i];
        }
        if (outPortTypes == null) {
            m_outPortTypes = new PortType[0];
        } else {
            m_outPortTypes = new PortType[outPortTypes.length];
        }
        for (int i = 0; i < outPortTypes.length; i++) {
            if (outPortTypes[i] == null) {
                throw new NullPointerException("OutPortType[" + i
                        + "] must not be null!");
            }
            m_outPortTypes[i] = outPortTypes[i];
        }

        m_hasContent = false;

        // set initial array of HiLiteHandlers
        if (getNrInPorts() == 0) {
            // initialize a new one if no input exists
            m_inHiLiteHdls = new HiLiteHandler[1];
        } else {
            // otherwise create a spot handlers - one for each input.
            m_inHiLiteHdls = new HiLiteHandler[getNrInPorts()];
        }

        // keeps set of registered views in the order they are added
        m_views = new CopyOnWriteArrayList<AbstractNodeView<?>>();
    }

    /**
     * Load internals into the derived <code>NodeModel</code>. This method is
     * only called if the <code>Node</code> was executed. Read all your
     * internal structures from the given file directory to create your internal
     * data structure which is necessary to provide all node functionalities
     * after the workflow is loaded, e.g. view content and/or hilite mapping.
     * <br>
     *
     * @param nodeInternDir The directory to read from.
     * @param exec Used to report progress and to cancel the load process.
     * @throws IOException If an error occurs during reading from this dir.
     * @throws CanceledExecutionException If the loading has been canceled.
     * @see #saveInternals(File,ExecutionMonitor)
     */
    protected abstract void loadInternals(final File nodeInternDir,
            final ExecutionMonitor exec)
            throws IOException, CanceledExecutionException;

    /**
     * Save internals of the derived <code>NodeModel</code>. This method is
     * only called if the <code>Node</code> is executed. Write all your
     * internal structures into the given file directory which are necessary to
     * recreate this model when the workflow is loaded, e.g. view content and/or
     * hilite mapping.<br>
     *
     * @param nodeInternDir The directory to write into.
     * @param exec Used to report progress and to cancel the save process.
     * @throws IOException If an error occurs during writing to this dir.
     * @throws CanceledExecutionException If the saving has been canceled.
     * @see #loadInternals(File,ExecutionMonitor)
     */
    protected abstract void saveInternals(final File nodeInternDir,
            final ExecutionMonitor exec)
            throws IOException, CanceledExecutionException;


    /**
     * Registers the given view at the model to receive change events of the
     * underlying model. Note that no change event is fired.
     *
     * @param view The view to register.
     */
    final void registerView(final AbstractNodeView<?> view) {
        assert view != null;
        m_views.add(view);
        m_logger.debug("Registering view at model (total count "
                + m_views.size() + ")");
    }

    /**
     * Unregisters the given view.
     *
     * @param view The view to unregister.
     */
    final void unregisterView(final AbstractNodeView<?> view) {
        assert view != null;
        boolean success = m_views.remove(view);
        if (success) {
            m_logger.debug("Unregistering view from model ("
                    + m_views.size() + " remaining).");
        } else {
            m_logger.debug("Can't remove view from model, not registered.");
        }
    }

    /**
     * Unregisters all views from the model.
     */
    final void unregisterAllViews() {
        m_logger.debug("Removing all (" + m_views.size()
                + ") views from model.");
        for (AbstractNodeView<?> view : m_views) {
            view.closeView();
        }
        m_views.clear();
    }

    /**
     * @return All registered views.
     */
    final Collection<AbstractNodeView<?>> getViews() {
        return Collections.unmodifiableCollection(m_views);
    }

    /**
     * Returns the overall number of inputs.
     *
     * @return Number of inputs.
     */
    protected final int getNrInPorts() {
        return m_inPortTypes.length;
    }

    /**
     * Returns the overall number of outputs.
     *
     * @return Number of outputs.
     */
    protected final int getNrOutPorts() {
        return m_outPortTypes.length;
    }

    /**
     * @param index
     * @return Type of the specified input port
     */
    final PortType getInPortType(final int index) {
        return m_inPortTypes[index];
    }

    /**
     * @param index
     * @return Type of the specified output port
     */
    final PortType getOutPortType(final int index) {
        return m_outPortTypes[index];
    }

    /**
     * Validates the specified settings in the model and then loads them into
     * it.
     *
     * @param settings the settings to read
     * @throws InvalidSettingsException if the settings are not valid or cannot
     *      be loaded into the model
     */
    final void loadSettingsFrom(final NodeSettingsRO settings)
            throws InvalidSettingsException {
        // validate the settings before loading them
        validateSettings(settings);
        // load settings into the model
        loadValidatedSettingsFrom(settings);
    }

    /**
     * Adds to the given <code>NodeSettings</code> the model specific
     * settings. The settings don't need to be complete or consistent. If, right
     * after startup, no valid settings are available this method can write
     * either nothing or invalid settings.
     * <p>
     * Method is called by the <code>Node</code> if the current settings need
     * to be saved or transfered to the node's dialog.
     *
     * @param settings The object to write settings into.
     *
     * @see #loadValidatedSettingsFrom(NodeSettingsRO)
     * @see #validateSettings(NodeSettingsRO)
     */
    protected abstract void saveSettingsTo(final NodeSettingsWO settings);

    /**
     * Validates the settings in the passed <code>NodeSettings</code> object.
     * The specified settings should be checked for completeness and
     * consistency. It must be possible to load a settings object validated
     * here without any exception in the
     * <code>#loadValidatedSettings(NodeSettings)</code> method. The method
     * must not change the current settings in the model - it is supposed to
     * just check them. If some settings are missing, invalid, inconsistent, or
     * just not right throw an exception with a message useful to the user.
     *
     * @param settings The settings to validate.
     * @throws InvalidSettingsException If the validation of the settings
     *             failed.
     * @see #saveSettingsTo(NodeSettingsWO)
     * @see #loadValidatedSettingsFrom(NodeSettingsRO)
     */
    protected abstract void validateSettings(final NodeSettingsRO settings)
            throws InvalidSettingsException;

    /**
     * Sets new settings from the passed object in the model. You can safely
     * assume that the object passed has been successfully validated by the
     * <code>#validateSettings(NodeSettings)</code> method. The model must set
     * its internal configuration according to the settings object passed.
     *
     * @param settings The settings to read.
     *
     * @throws InvalidSettingsException If a property is not available.
     *
     * @see #saveSettingsTo(NodeSettingsWO)
     * @see #validateSettings(NodeSettingsRO)
     */
    protected abstract void loadValidatedSettingsFrom(
            final NodeSettingsRO settings) throws InvalidSettingsException;

    /**
     * Invokes the abstract <code>#execute()</code> method of this model. In
     * addition, this method notifies all assigned views of the model about the
     * changes.
     *
     * @param data An array of <code>DataTable</code> objects holding the data
     *            from the inputs.
     * @param exec The execution monitor which is passed to the execute method
     *            of this model.
     * @return The result of the execution in form of an array with
     *         <code>DataTable</code> elements, as many as the node has
     *         outputs.
     * @throws Exception any exception or error that is fired in the derived
     *             model will be just forwarded. It may throw an
     *             CanceledExecutionException if the user pressed cancel during
     *             execution. Even if the derived model doesn't check, the
     *             result will be discarded and the exception thrown.
     * @throws IllegalStateException If the number of <code>PortObject</code>
     *             objects returned by the derived <code>NodeModel</code>
     *             does not match the number of outputs. Or if any of them is
     *             null.
     * @see #execute(PortObject[],ExecutionContext)
     */
    protected final PortObject[] executeModel(final PortObject[] data,
            final ExecutionContext exec) throws Exception {
        assert (data != null && data.length == getNrInPorts());
        assert (exec != null);

        setWarningMessage(null);

        // temporary storage for result of derived model.
        // EXECUTE DERIVED MODEL
        PortObject[] outData = execute(data, exec);

        // if execution was canceled without exception flying return false
        if (exec.isCanceled()) {
            throw new CanceledExecutionException(
                    "Result discarded due to user cancel");
        }

        if (outData == null) {
            outData = new PortObject[getNrOutPorts()];
        }

        /* Cleanup operation for nodes that just pass on their input
         * data table. We need to wrap those here so that the framework
         * explicitly references them (instead of copying) */
        for (int i = 0; i < outData.length; i++) {
            if (outData[i] instanceof BufferedDataTable) {
                for (int j = 0; j < data.length; j++) {
                    if (outData[i] == data[j]) {
                        outData[i] = exec.createWrappedTable(
                                (BufferedDataTable)data[j]);
                    }
                }
            }
        }

        // TODO: check outgoing types! (inNode!)

        // if number of out tables does not match: fail
        if (outData.length != getNrOutPorts()) {
            throw new IllegalStateException(
                    "Invalid result. Execution failed. "
                            + "Reason: Incorrect implementation; the execute"
                            + " method in " + this.getClass().getSimpleName()
                            + " returned null or an incorrect number of output"
                            + " tables.");
        }

        // check the result, data tables must not be null
        for (int i = 0; i < outData.length; i++) {
            // do not check for null output tables if this is the end node
            // of a loop and another loop iteration is requested
            if ((getLoopStatus() == null) && (outData[i] == null)) {
                m_logger.error("Execution failed: Incorrect implementation;"
                        + " the execute method in "
                        + this.getClass().getSimpleName()
                        + "returned a null data table at port: " + i);
                throw new IllegalStateException("Invalid result. "
                        + "Execution failed, reason: data at output " + i
                        + " is null.");
            }
        }
        // check meaningfulness of result and warn,
        // - only if the execute didn't issue a warning already
        if ((m_warningMessage == null) || (m_warningMessage.length() == 0)) {
            boolean hasData = false;
            int bdtPortCount = 0; // number of BDT ports
            for (int i = 0; i < outData.length; i++) {
                if (outData[i] instanceof BufferedDataTable) {
                    // do some sanity checks on PortObjects holding data tables
                    bdtPortCount += 1;
                    BufferedDataTable outDataTable =
                        (BufferedDataTable)outData[i];
                    if (outDataTable.getRowCount() > 0) {
                        hasData = true;
                    } else {
                        m_logger.info("The result table at port " + i
                                + " contains no rows");
                    }
                }
            }
            if (!hasData && bdtPortCount > 0) {
                if (bdtPortCount == 1) {
                    setWarningMessage("Node created an empty data table.");
                } else {
                    setWarningMessage(
                            "Node created empty data tables on all out-ports.");
                }
            }
        }

        setHasContent(true);
        return outData;
    } // executeModel(PortObject[],ExecutionMonitor)

    /**
     * Sets the hasContent flag and fires a state change event.
     * @param hasContent Flag if this node is configured be executed or not.
     */
    final void setHasContent(final boolean hasContent) {
        if (hasContent != hasContent()) {
            m_hasContent = hasContent;
            // and inform all views about the new model
            stateChanged();
        }
    }

    /**
     * @return <code>true</code> if this model has been executed and therefore
     * possibly has content that can be displayed in a view,
     * <code>false</code> otherwise.
     */
    final boolean hasContent() {
        return m_hasContent;
    }

    /**
     * Execute method for general port types. The argument objects represent the
     * input objects and are guaranteed to be subclasses of the
     * {@link PortObject PortObject classes} that are defined through the
     * {@link PortType PortTypes} given in the
     * {@link NodeModel#NodeModel(PortType[], PortType[]) constructor}.
     * Similarly, the returned output objects need to comply with their port
     * types object class (otherwise an error is reported by the framework).
     *
     * <p>
     * For a general description of the execute method refer to the description
     * of the specialized
     * {@link #execute(BufferedDataTable[], ExecutionContext)} methods as it
     * addresses more use cases.
     *
     * @param inObjects The input objects.
     * @param exec For {@link BufferedDataTable} creation and progress.
     * @return The output objects.
     * @throws Exception If the node execution fails for any reason.
     */
    protected PortObject[] execute(final PortObject[] inObjects,
            final ExecutionContext exec) throws Exception {
        // default implementation: the standard version needs to hold: all
        // ports are data ports!

        // (1) case PortObjects to BufferedDataTable
        BufferedDataTable[] inTables = new BufferedDataTable[inObjects.length];
        for (int i = 0; i < inObjects.length; i++) {
            try {
                inTables[i] = (BufferedDataTable)inObjects[i];
            } catch (ClassCastException cce) {
                throw new IOException("Input Port " + i
                        + " does not hold data table specs. "
                        + "Likely reason: wrong version"
                        + " of NodeModel.execute() overwritten!");
            }
        }
        // (2) call old-fashioned, data-only execute
        BufferedDataTable[] outData = execute(inTables, exec);
        // (3) return new POs (upcast from BDT automatic)
        return outData;
    }

    /**
     * This function is invoked by the <code>Node#executeNode()</code> method
     * of the node (through the
     * <code>#executeModel(BufferedDataTable[],ExecutionMonitor)</code>
     * method)only after all predecessor nodes have been successfully executed
     * and all data is therefore available at the input ports. Implement this
     * function with your task in the derived model.
     * <p>
     * The input data is available in the given array argument
     * <code>inData</code> and is ensured to be neither <code>null</code>
     * nor contain <code>null</code> elements.
     *
     * <p>
     * In order to create output data, you need to create objects of class
     * <code>BufferedDataTable</code>. Use the execution context argument to
     * create <code>BufferedDataTable</code>.
     *
     * @param inData An array holding <code>DataTable</code> elements, one for
     *            each input.
     * @param exec The execution monitor for this execute method. It provides us
     *            with means to create new <code>BufferedDataTable</code>.
     *            Additionally, it should be asked frequently if the execution
     *            should be interrupted and throws an exception then. This
     *            exception might me caught, and then after closing all data
     *            streams, been thrown again. Also, if you can tell the progress
     *            of your task, just set it in this monitor.
     * @return An array of non- <code>null</code> DataTable elements with the
     *         size of the number of outputs. The result of this execution.
     * @throws Exception If you must fail the execution. Try to provide a
     *             meaningful error message in the exception as it will be
     *             displayed to the user.<STRONG>Please</STRONG> be advised to
     *             check frequently the canceled status by invoking
     *             <code>ExecutionMonitor#checkCanceled</code> which will
     *             throw an <code>CanceledExcecutionException</code> and abort
     *             the execution.
     */
    protected BufferedDataTable[] execute(final BufferedDataTable[] inData,
            final ExecutionContext exec)
        throws Exception {
            throw new IOException(
                    "NodeModel.execute() implementation missing!");
    }

    /**
     * Invokes the abstract <code>#reset()</code> method of the derived model.
     * In addition, this method resets all hilite handlers, and notifies all
     * views about the changes.
     */
    final void resetModel() {
        try {
            setWarningMessage(null);
            // reset in derived model
            reset();
        } catch (Throwable t) {
            String name = t.getClass().getSimpleName();
            m_logger.coding("Reset failed due to a " + name, t);
        } finally {
            // set state to not executed and not configured
            m_hasContent = false;
            // reset these property handlers
            resetHiLiteHandlers();
            // and notify all views
            stateChanged();
        }
    }

    /**
     * Override this function in the derived model and reset your
     * <code>NodeModel</code>. All components should unregister themselves
     * from any observables (at least from the hilite handler right now). All
     * internally stored data structures should be released. User settings
     * should not be deleted/reset though.
     */
    protected abstract void reset();

    /**
     * Notifies all registered views of a change of the underlying model. It is
     * called by functions of the abstract class that modify the model (like
     * <code>#executeModel()</code> and <code>#resetModel()</code> ).
     */
    protected final void stateChanged() {
        for (AbstractNodeView<?> view : m_views) {
            try {
                view.callModelChanged();
            } catch (Exception e) {
                setWarningMessage("View [" + view.getViewName()
                        + "] could not be open, reason: " + e.getMessage());
                m_logger.debug("View [" + view.getViewName()
                        + "] could not be open, reason: " + e.getMessage(), e);
            }
        }
    }

    /**
     * This method can be called from the derived model in order to inform the
     * views about changes of the settings or during execution, if you want the
     * views to show the progress, and if they can display models half way
     * through the execution. In the view
     * <code>NodeView#updateModel(Object)</code> is called and needs to
     * be overridden.
     *
     * @param arg The argument you want to pass.
     */
    protected final void notifyViews(final Object arg) {
        for (AbstractNodeView<?> view : m_views) {
            view.updateModel(arg);
        }
    }

    /**
     * This implementation is empty. Subclasses may override this method
     * in order to be informed when the hilite handler changes at the inport,
     * e.g. when the node (or an preceding node) is newly connected.
     *
     * @param inIndex The index of the input.
     * @param hiLiteHdl The <code>HiLiteHandler</code> at input index.
     *         May be <code>null</code> when not available, i.e. not properly
     *         connected.
     * @throws IndexOutOfBoundsException If the <code>inIndex</code> is not in
     *          the range of inputs.
     */
    protected void setInHiLiteHandler(final int inIndex,
            final HiLiteHandler hiLiteHdl) {
        assert inIndex >= 0;
        assert hiLiteHdl == hiLiteHdl;
    }

    /**
     * Sets a new <code>HiLiteHandler</code> for the given input.
     *
     * This method is called by the corresponding <code>Node</code> in order
     * to set the <code>HiLiteHandler</code> for the given input port.
     *
     * @param in The input index.
     * @param hdl The new <code>HiLiteHandler</code>.
     *
     * @see #setInHiLiteHandler(int, HiLiteHandler)
     */
    final void setNewInHiLiteHandler(final int in, final HiLiteHandler hdl) {
        if (m_inHiLiteHdls[in] == hdl) {
            // only do something (calls notification and state change!)
            // if there really is a new HiLiteHandler.
            return;
        }
        m_inHiLiteHdls[in] = hdl;
        int simulatedPortIndex = getSimulatedHiliteHandlerPortIndex(in);
        if (simulatedPortIndex >= 0) {
            setInHiLiteHandler(simulatedPortIndex, hdl);
        }
        stateChanged();
    }

    /**
     * Returns the <code>HiLiteHandler</code> for the given input index, if the
     * current in-port hilite handler is <code>null</code> an
     * <code>HiLiteHandlerAdapter</code> is created and returned.
     *
     * @param inIndex in-port index
     * @return <code>HiLiteHandler</code> for the given input index
     * @throws IndexOutOfBoundsException if the <code>inIndex</code> is not in
     *             the range of inputs
     */
    public final HiLiteHandler getInHiLiteHandler(final int inIndex) {
        int correctIndex = getTrueHiliteHandlerPortIndex(inIndex);
        if (m_inHiLiteHdls[correctIndex] == null) {
            return HILITE_ADAPTER;
        }
        return m_inHiLiteHdls[correctIndex];
    }

    /** Returns the argument. This method is overridden in class
     * {@link NodeModel} to handle incoming model ports appropriately
     * (no hilite handlers at deprecated model ports).
     * @param portIndex The simulated port index
     * @return The true port index
     */
    int getTrueHiliteHandlerPortIndex(final int portIndex) {
        return portIndex;
    }

    /** Returns the argument. This method is overridden in class
     * {@link NodeModel} to handle incoming model ports appropriately
     * (no hilite handlers at deprecated model ports).
     * @param portIndex The true port index
     * @return The simulated port index
     */
    int getSimulatedHiliteHandlerPortIndex(final int portIndex) {
        return portIndex;
    }

    /**
     * Returns the <code>HiLiteHandler</code> for the given output index. This
     * default implementation simply passes on the handler of input port 0 or
     * generates a new one if this node has no inputs. <br>
     * <br>
     * This method is intended to be overridden
     *
     * @param outIndex The output index.
     * @return <code>HiLiteHandler</code> for the given output port.
     * @throws IndexOutOfBoundsException If the index is not in output's range.
     */
    protected HiLiteHandler getOutHiLiteHandler(final int outIndex) {
        if (outIndex < 0 || outIndex >= getNrOutPorts()) {
            throw new IndexOutOfBoundsException("index=" + outIndex);
        }
        // if we have no inputs we create a new instance (but only one...)
        if (getNrInPorts() == 0) {
            if (m_inHiLiteHdls[0] == null) {
                m_inHiLiteHdls[0] = new HiLiteHandler();
            }
            return m_inHiLiteHdls[0];
        }
        int firstBDTPort = 0;
        for (int i = 0; i < getNrInPorts(); i++) {
            if (getInPortType(i).equals(BufferedDataTable.TYPE)) {
                firstBDTPort = i;
                break;
            }
        }
        return getInHiLiteHandler(firstBDTPort);
    }

    /**
     * Resets all internal <code>HiLiteHandler</code> objects if the number of
     * inputs is zero.
     */
    private void resetHiLiteHandlers() {
        // if we have no inputs we have created a new instance.
        // we need to reset it here then.
        if (getNrInPorts() == 0) {
            for (int i = 0; i < m_inHiLiteHdls.length; i++) {
                if (m_inHiLiteHdls[i] != null) {
                    m_inHiLiteHdls[i].fireClearHiLiteEvent();
                }
            }
        }
    }

    /**
     * This function is called when something changes that could affect the
     * output <code>DataTableSpec</code> elements. E.g. after a reset,
     * execute, (dis-)connect, and object creation (model instantiation).
     * <p>
     * The function calls <code>#configure()</code> to receive the updated
     * output DataTableSpecs, if the model is not executed yet. After execution
     * the DataTableSpecs are simply taken from the output DataTables.
     *
     * @param inSpecs An array of input <code>DataTableSpec</code> elements,
     *            either the array or each of its elements can be
     *            <code>null</code>.
     * @return An array where each element indicates if the outport has changed.
     * @throws InvalidSettingsException if the current settings don't go along
     *             with the table specs
     */
    final PortObjectSpec[] configureModel(final PortObjectSpec[] inSpecs)
            throws InvalidSettingsException {
        assert inSpecs.length == getNrInPorts();

        setWarningMessage(null);

        PortObjectSpec[] copyInSpecs = new PortObjectSpec[getNrInPorts()];
        PortObjectSpec[] newOutSpecs;

        System.arraycopy(inSpecs, 0, copyInSpecs, 0, inSpecs.length);
        // make sure we conveniently have TableSpecs.
        // Rather empty ones than null
        for (int i = 0; i < copyInSpecs.length; i++) {
            if (copyInSpecs[i] == null
                    && BufferedDataTable.TYPE.equals(m_inPortTypes[i])
                    && !m_inPortTypes[i].isOptional()) {
                // only mimic empty table for real table connections
                copyInSpecs[i] = new DataTableSpec();
            }
            // only weak port compatibility check during connect
            // (model reader can be connected to any model port)
            // complain if actual types are incompatible.
            Class<? extends PortObjectSpec> expected =
                m_inPortTypes[i].getPortObjectSpecClass();
            if (copyInSpecs[i] != null
                    && !expected.isAssignableFrom(copyInSpecs[i].getClass())) {
                StringBuilder b = new StringBuilder("Incompatible port spec");
                if (copyInSpecs.length > 1) {
                    b.append(" at port ").append(i);
                }
                b.append(", expected: ").append(expected.getSimpleName());
                b.append(", actual: ").append(
                        copyInSpecs[i].getClass().getSimpleName());
                throw new InvalidSettingsException(b.toString());
            }
        }

        // CALL CONFIGURE
        newOutSpecs = configure(copyInSpecs);
        if (newOutSpecs == null) {
            newOutSpecs = new PortObjectSpec[getNrOutPorts()];
        }
        // check output object spec length
        if (newOutSpecs.length != getNrOutPorts()) {
            m_logger.error("Output spec-array length invalid: "
                    + newOutSpecs.length + " <> " + getNrOutPorts());
            newOutSpecs = new PortObjectSpec[getNrOutPorts()];
        }
        return newOutSpecs;
    }

    /**
     * Configure method for general port types. The argument specs represent the
     * input object specs and are guaranteed to be subclasses of the
     * {@link PortObjectSpec PortObjectSpecs} that are defined through the
     * {@link PortType PortTypes} given in the
     * {@link NodeModel#NodeModel(PortType[], PortType[]) constructor}.
     * Similarly, the returned output specs need to comply with their port types
     * spec class (otherwise an error is reported by the framework). They may
     * also be null.
     *
     * <p>
     * For a general description of the configure method refer to the
     * description of the specialized {@link #configure(DataTableSpec[])}
     * methods as it addresses more use cases.
     *
     * @param inSpecs The input object specs.
     * @return The output objects specs or null.
     * @throws InvalidSettingsException If this node can't be configured.
     */
    protected PortObjectSpec[] configure(final PortObjectSpec[] inSpecs)
    throws InvalidSettingsException {
        // default implementation: the standard version needs to hold: all
        // ports are data ports!

        // (1) case PortObjectSpecs to DataTableSpecs
        DataTableSpec[] inDataSpecs = new DataTableSpec[inSpecs.length];
        for (int i = 0; i < inSpecs.length; i++) {
            try {
                inDataSpecs[i] = (DataTableSpec)inSpecs[i];
            } catch (ClassCastException cce) {
                throw new InvalidSettingsException("Input Port " + i
                        + " does not hold data table specs. "
                        + "Likely reason: wrong version"
                        + " of NodeModel.configure() overwritten!");
            }
        }
        // (2) call old-fashioned, data-only configure
        DataTableSpec[] outDataSpecs = configure(inDataSpecs);
        // (3) return new POs (upcast from DataSpecs automatic)
        return outDataSpecs;
    }

    /**
     * This function is called whenever the derived model should re-configure
     * its output DataTableSpecs. Based on the given input data table spec(s)
     * and the current model's settings, the derived model has to calculate the
     * output data table spec and return them.
     * <p>
     * The passed DataTableSpec elements are never <code>null</code> but can
     * be empty. The model may return <code>null</code> data table spec(s) for
     * the outputs. But still, the model may be in an executable state. Note,
     * after the model has been executed this function will not be called
     * anymore, as the output DataTableSpecs are then being pulled from the
     * output DataTables. A derived <code>NodeModel</code> that cannot provide
     * any DataTableSpecs at its outputs before execution (because the table
     * structure is unknown at this point) can return an array
     * containing just <code>null</code> elements.
     *
     * <p>
     * Implementation note: This method is called from the
     * {@link #configure(PortObjectSpec[])} method unless that method is
     * overwritten.
     *
     * @param inSpecs An array of DataTableSpecs (as many as this model has
     *            inputs). Do NOT modify the contents of this array. None of the
     *            DataTableSpecs in the array can be <code>null</code> but
     *            empty. If the predecessor node is not yet connected, or
     *            doesn't provide a DataTableSpecs at its output port.
     * @return An array of DataTableSpecs (as many as this model has outputs)
     *         They will be propagated to connected successor nodes.
     *         <code>null</code> DataTableSpec elements are changed to empty
     *         once.
     *
     * @throws InvalidSettingsException if the <code>#configure()</code>
     *             failed, that is, the settings are inconsistent with given
     *             DataTableSpec elements.
     */
    protected DataTableSpec[] configure(final DataTableSpec[] inSpecs)
    throws InvalidSettingsException {
        throw new InvalidSettingsException("NodeModel.configure()"
                + " implementation missing!");
    }

    /////////////////////////
    // Warning handling
    /////////////////////////

    /** Method being called when node is restored. It does not notify listeners.
     * @param warningMessage The message as written to the workflow file
     */
    final void restoreWarningMessage(final String warningMessage) {
        m_warningMessage = warningMessage;
    }

    /**
     * Sets an optional warning message by the implementing node model.
     *
     * @param warningMessage the warning message to set
     */
    protected final void setWarningMessage(final String warningMessage) {
        // message changed, set new one and notify listeners.
        m_warningMessage = warningMessage;
        notifyWarningListeners(m_warningMessage);
    }

    /**
     * Get the most recently set warning message.
     * @return the warningMessage that is currently set (or null)
     */
    protected final String getWarningMessage() {
        return m_warningMessage;
    }

    /**
     * Adds a warning listener to this node. Ignored if the listener is already
     * registered.
     *
     * @param listener The listener to add.
     */
    public void addWarningListener(final NodeModelWarningListener listener) {
        if (listener == null) {
            throw new NullPointerException(
                    "NodeModel message listener must not be null!");
        }
        m_warningListeners.add(listener);
    }

    /**
     * Removes a warning listener from this node. Ignored if the listener is
     * not registered.
     *
     * @param listener The listener to remove.
     */
    public void removeWarningListener(
            final NodeModelWarningListener listener) {
        if (!m_warningListeners.remove(listener)) {
            m_logger.debug("listener was not registered: " + listener);
        }
    }

    /**
     * Notifies all listeners that the warning of this node has changed.
     * @param warning message
     */
    public void notifyWarningListeners(final String warning) {
        for (NodeModelWarningListener listener : m_warningListeners) {
            try {
                listener.warningChanged(warning);
            } catch (Throwable t) {
                m_logger.error("Exception while notifying NodeModel listeners",
                        t);
            }
        }
    }

    /** Holds the {@link FlowObjectStack} of this node. */
    private FlowObjectStack m_flowObjectStack;

    /** Get the value of the String variable with the given name leaving the
     * flow variable stack unmodified.
     * @param name Name of the variable
     * @return The value of the string variable
     * @throws NullPointerException If the argument is null
     * @throws NoSuchElementException If no such variable with the correct
     * type is available.
     */
    protected final String peekFlowVariableString(final String name) {
        return m_flowObjectStack.peekFlowVariable(
                name, FlowVariable.Type.STRING).getStringValue();
    }

    /** Put a new variable of type double onto the stack. If such variable
     * already exists, its value will be (virtually) overwritten.
     * @param name The name of the variable.
     * @param value The assignment value for the variable
     * @throws NullPointerException If the name argument is null.
     */
    protected final void pushFlowVariableDouble(
            final String name, final double value) {
        m_flowObjectStack.push(new FlowVariable(name, value));
    }

    /** Get the value of the double variable with the given name leaving the
     * variable stack unmodified.
     * @param name Name of the variable
     * @return The assignment value of the variable
     * @throws NullPointerException If the argument is null
     * @throws NoSuchElementException If no such variable with the correct
     * type is available.
     */
    protected final double peekFlowVariableDouble(final String name) {
        return m_flowObjectStack.peekFlowVariable(
                name, FlowVariable.Type.DOUBLE).getDoubleValue();
    }

    /** Put a new variable of type integer onto the stack. If such variable
     * already exists, its value will be (virtually) overwritten.
     * @param name The name of the variable.
     * @param value The assignment value for the variable
     * @throws NullPointerException If the name argument is null.
     */
    protected final void pushFlowVariableInt(
            final String name, final int value) {
        m_flowObjectStack.push(new FlowVariable(name, value));
    }

    /** Get the value of the integer variable with the given name leaving the
     * variable stack unmodified.
     * @param name Name of the variable
     * @return The value of the integer variable
     * @throws NullPointerException If the argument is null
     * @throws NoSuchElementException If no such variable with the correct
     * type is available.
     */
    protected final int peekFlowVariableInt(final String name) {
        return m_flowObjectStack.peekFlowVariable(
                name, FlowVariable.Type.INTEGER).getIntValue();
    }

    /** Put a new variable of type String onto the stack. If such variable
     * already exists, its value will be (virtually) overwritten.
     * @param name The name of the variable.
     * @param value The assignment value for the variable
     * @throws NullPointerException If the name argument is null.
     */
    protected final void pushFlowVariableString(
            final String name, final String value) {
        m_flowObjectStack.push(new FlowVariable(name, value));
    }

    /** Informs WorkflowManager after execute to continue the loop.
     * Call by the end of the loop! This will result in both
     * this Node as well as the creator of the FlowLoopContext to be
     * queued for execution once again. In this case the node can return
     * an empty table after execution.
     */
    protected final void continueLoop() {
        FlowLoopContext slc = m_flowObjectStack.peek(
                FlowLoopContext.class);
        if (slc == null) {
            // wrong wiring of the pipeline: head seems to be missing!
            throw new IllegalStateException(
                    "Missing Loop Start in Pipeline!");
        }
        m_loopStatus = slc;
        // note that the WFM will set the tail ID so we can retrieve it
        // in the head node!
    }

    private FlowLoopContext m_loopStatus;

    final FlowLoopContext getLoopStatus() {
        return m_loopStatus;
    }

    final void clearLoopStatus() {
        m_loopStatus = null;
    }

    private LoopEndNode m_loopEndNode = null;

    /** Access method for loop start nodes to access their respective
     * loop end. This method returns null if this node is not a loop start or
     * the loop is not correctly closed by the user.
     * @return The loop end node or null. Clients typically test and cast to
     * an expected loop end instance.
     * @see #getLoopStartNode()
     */
    protected final LoopEndNode getLoopEndNode() {
        return m_loopEndNode;
    }

    /** Setter used by framework to update loop end node.
     * @param end The end node of the loop (if this is a start node). */
    void setLoopEndNode(final LoopEndNode end) {
        m_loopEndNode = end;
    }

    private LoopStartNode m_loopStartNode = null;

    /** Access method for loop end nodes to access their respective loop start.
     * This method returns null if this node is not a loop end or the loop is
     * not correctly closed by the user.
     * @return The loop start node or null. Clients typically test and cast to
     * an expected loop start instance.
     * @see #getLoopEndNode()
     */
    protected final LoopStartNode getLoopStartNode() {
        return m_loopStartNode;
    }

    /** Setter used by framework to update loop start node.
     * @param start The start node of the loop (if this is a end node). */
    void setLoopStartNode(final LoopStartNode start) {
        m_loopStartNode = start;
    }

    FlowObjectStack getFlowObjectStack() {
        return m_flowObjectStack;
    }

    void setFlowObjectStack(final FlowObjectStack scsc) {
        m_flowObjectStack = scsc;
    }

    /** @deprecated This method has been replaced by
     * {@link #pushFlowVariableString(String, String)}.
     * It will be removed in future versions.
     */
    @Deprecated
    protected final void pushScopeVariableString(
            final String name, final String value) {
        pushFlowVariableString(name, value);
    }

    /** @deprecated This method has been replaced by
     * {@link #peekFlowVariableString(String)}.
     * It will be removed in future versions.
     */
    @Deprecated
    protected final String peekScopeVariableString(final String name) {
        return peekFlowVariableString(name);
    }

    /** @deprecated This method has been replaced by
     * {@link #pushFlowVariableDouble(String, double)}.
     * It will be removed in future versions.
     */
    @Deprecated
    protected final void pushScopeVariableDouble(
            final String name, final double value) {
        pushFlowVariableDouble(name, value);
    }

    /** @deprecated This method has been replaced by
     * {@link #peekFlowVariableDouble(String)}.
     * It will be removed in future versions.
     */
    @Deprecated
    protected final double peekScopeVariableDouble(final String name) {
        return peekFlowVariableDouble(name);
    }

    /** @deprecated This method has been replaced by
     * {@link #pushFlowVariableInt(String, int)}.
     * It will be removed in future versions.
     */
    @Deprecated
    protected final void pushScopeVariableInt(
            final String name, final int value) {
        pushFlowVariableInt(name, value);
    }

    /** @deprecated This method has been replaced by
     * {@link #peekFlowVariableInt(String)}.
     * It will be removed in future versions.
     */
    @Deprecated
    protected final int peekScopeVariableInt(final String name) {
        return peekFlowVariableInt(name);
    }

}

