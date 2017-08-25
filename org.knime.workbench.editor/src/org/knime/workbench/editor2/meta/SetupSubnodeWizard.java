/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME GmbH, Konstanz, Germany
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
 * ---------------------------------------------------------------------
 *
 * History
 *   23 Aug 2017 (albrecht): created
 */
package org.knime.workbench.editor2.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.gef.EditPartViewer;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.swt.widgets.Button;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeModel;
import org.knime.core.node.dialog.DialogNode;
import org.knime.core.node.dialog.InputNode;
import org.knime.core.node.dialog.OutputNode;
import org.knime.core.node.port.MetaPortInfo;
import org.knime.core.node.wizard.WizardNode;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.SubNodeContainer;
import org.knime.core.node.workflow.WorkflowManager;
import org.knime.workbench.KNIMEEditorPlugin;
import org.knime.workbench.core.util.ImageRepository;
import org.knime.workbench.editor2.commands.ReconfigureMetaNodeCommand;

/**
 * Wizard to configure the setup of a wrapped metanode,
 * e.g. changing the name or the number and type of in
 * and out ports.
 *
 * @author Christian Albrecht, KNIME GmbH, Konstanz, Germany
 */
public class SetupSubnodeWizard extends Wizard {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(SetupSubnodeWizard.class);

    private ConfigureMetaNodePortsPage m_portsPage;
    private ConfigureNodeUsagePage m_usagePage;

    private final EditPartViewer m_viewer;
    private final SubNodeContainer m_subNode;

    /**
     * @param viewer The viewer
     * @param subNode The subnode container
     */
    public SetupSubnodeWizard(final EditPartViewer viewer, final SubNodeContainer subNode) {
        super();
        m_subNode = subNode;
        m_viewer = viewer;
        setHelpAvailable(false);
    }

    /**
    *
    * {@inheritDoc}
    */
   @Override
   public void addPages() {
       setWindowTitle("Setup Wrapped Metanode Wizard");
       setDefaultPageImageDescriptor(ImageDescriptor.createFromImage(
               ImageRepository.getImage(KNIMEEditorPlugin.PLUGIN_ID, "icons/meta/meta_node_wizard2.png")));

       WorkflowManager wfManager = m_subNode.getWorkflowManager();
       Map<NodeID, NodeModel> allNodes = wfManager.findNodes(NodeModel.class, false);
       LinkedHashMap<NodeID, NodeModel> usageNodes = new LinkedHashMap<NodeID, NodeModel>();
       List<NodeID> nodeIDs = new ArrayList<NodeID>();
       nodeIDs.addAll(allNodes.keySet());
       Collections.sort(nodeIDs);
       for (NodeID id : nodeIDs) {
           NodeModel model = allNodes.get(id);
           if (considerNodeForUsage(model)) {
               usageNodes.put(id, model);
           }
       }

       m_portsPage = new ConfigureMetaNodePortsPage("Change the Wrapped Metanode configuration");
       m_portsPage.setSubNode(m_subNode);
       m_portsPage.setTemplate(null);
       addPage(m_portsPage);

       m_usagePage = new ConfigureNodeUsagePage("Change node usage configuration");
       m_usagePage.setNodes(m_subNode, usageNodes);
       addPage(m_usagePage);
   }

   private boolean considerNodeForUsage(final NodeModel model) {
       boolean consider = false;
       consider |= model instanceof WizardNode;
       consider |= model instanceof DialogNode;
       consider |= model instanceof InputNode;
       consider |= model instanceof OutputNode;
       return consider;
   }

   /**
   *
   * {@inheritDoc}
   */
  @Override
  public boolean canFinish() {
      return m_portsPage.isPageComplete() && m_usagePage.isPageComplete();
  }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean performFinish() {
        if (!applyPortChanges()) {
            return false;
        }
        return applyUsageChanges();
    }

    private boolean applyPortChanges() {
        List<MetaPortInfo> inPorts = m_portsPage.getInports();
        List<MetaPortInfo> outPorts = m_portsPage.getOutPorts();
        String name = m_portsPage.getMetaNodeName();

        // fix the indicies
        for (int i = 0; i < inPorts.size(); i++) {
            inPorts.get(i).setNewIndex(i);
        }
        for (int i = 0; i < outPorts.size(); i++) {
            outPorts.get(i).setNewIndex(i);
        }
        // determine what has changed
        boolean inPortChanges = m_subNode.getNrInPorts() != inPorts.size();
        for (MetaPortInfo inInfo : inPorts) {
            // new port types would create a new info object - which would have an unspecified old index -> change
            inPortChanges |= inInfo.getOldIndex() != inInfo.getNewIndex();
        }
        boolean outPortChanges = m_subNode.getNrOutPorts() != outPorts.size();
        for (MetaPortInfo outInfo : outPorts) {
            // new port types would create a new info object - which would have an unspecified old index -> change
            outPortChanges |= outInfo.getOldIndex() != outInfo.getNewIndex();
        }
        boolean nameChange = !m_subNode.getName().equals(name);
        StringBuilder infoStr = new StringBuilder();
        if (inPortChanges) {
            infoStr.append("the input ports - ");
        }
        if (outPortChanges) {
            infoStr.append("the output ports - ");
        }
        if (nameChange) {
            infoStr.append("the name - ");
        }
        if (infoStr.length() == 0) {
            LOGGER.info("No changes made in the configuration wizard. Nothing to do.");
            return true;
        }
        infoStr.insert(0, "Changing - ");
        infoStr.append("of MetaNode " + m_subNode.getID());
        LOGGER.info(infoStr);

        ReconfigureMetaNodeCommand reconfCmd = new ReconfigureMetaNodeCommand(m_subNode.getParent(),
            m_subNode.getID());
        if (nameChange) {
            reconfCmd.setNewName(name);
        }
        if (inPortChanges) {
            reconfCmd.setNewInPorts(inPorts);
        }
        if (outPortChanges) {
            reconfCmd.setNewOutPorts(outPorts);
        }
        m_viewer.getEditDomain().getCommandStack().execute(reconfCmd);
        return true;
    }

    private boolean applyUsageChanges() {
        WorkflowManager wfManager = m_subNode.getWorkflowManager();
        Map<NodeID, NodeModel> allNodes = wfManager.findNodes(NodeModel.class, false);
        for (Entry<NodeID, Button> wUsage : m_usagePage.getWizardUsageMap().entrySet()) {
            NodeModel model = allNodes.get(wUsage.getKey());
            if (model == null) {
                LOGGER.error("Node with ID " + wUsage.getKey() + " was not found in wrapped metanode.");
                return false;
            }
            if (!(model instanceof WizardNode)) {
                LOGGER.error("Node with ID " + wUsage.getKey() + " was not of type WizardNode.");
                return false;
            }
            WizardNode<?, ?> wNode = (WizardNode<?,?>)model;
            wNode.setHideInWizard(!wUsage.getValue().getSelection());
        }

        for (Entry<NodeID, Button> dUsage : m_usagePage.getDialogUsageMap().entrySet()) {
            NodeModel model = allNodes.get(dUsage.getKey());
            if (model == null) {
                LOGGER.error("Node with ID " + dUsage.getKey() + " was not found in wrapped metanode.");
                return false;
            }
            if (!(model instanceof DialogNode)) {
                LOGGER.error("Node with ID " + dUsage.getKey() + " was not of type DialogNode.");
                return false;
            }
            DialogNode<?, ?> dNode = (DialogNode<?,?>)model;
            dNode.setHideInDialog(!dUsage.getValue().getSelection());
        }

        for (Entry<NodeID, Button> iUsage : m_usagePage.getInterfaceUsageMap().entrySet()) {
            NodeModel model = allNodes.get(iUsage.getKey());
            if (model == null) {
                LOGGER.error("Node with ID " + iUsage.getKey() + " was not found in wrapped metanode.");
                return false;
            }
            if (!(model instanceof InputNode || model instanceof OutputNode)) {
                LOGGER.error("Node with ID " + iUsage.getKey() + " was not of type DialogNode.");
                return false;
            }
            if (model instanceof InputNode) {
                InputNode iNode = (InputNode)model;
                //TODO assign value when implemented
            } else if (model instanceof OutputNode) {
                OutputNode oNode = (OutputNode)model;
                //TODO assign value when implemented
            }
        }

        return true;
    }

}