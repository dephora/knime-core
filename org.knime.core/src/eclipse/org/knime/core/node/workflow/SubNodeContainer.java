/*
 * ------------------------------------------------------------------------
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
 * Created on Oct 1, 2013 by Berthold
 */
package org.knime.core.node.workflow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.xmlbeans.XmlException;
import org.knime.core.data.container.ContainerTable;
import org.knime.core.data.filestore.internal.FileStoreHandlerRepository;
import org.knime.core.internal.ReferencedFile;
import org.knime.core.node.AbstractNodeView;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.Node;
import org.knime.core.node.NodeConfigureHelper;
import org.knime.core.node.NodeDescription27Proxy;
import org.knime.core.node.NodeDialogPane;
import org.knime.core.node.NodeFactory.NodeType;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeSettings;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.NotConfigurableException;
import org.knime.core.node.dialog.DialogNode;
import org.knime.core.node.dialog.DialogNodeRepresentation;
import org.knime.core.node.dialog.DialogNodeValue;
import org.knime.core.node.dialog.MetaNodeDialogNode;
import org.knime.core.node.exec.ThreadNodeExecutionJobManager;
import org.knime.core.node.interactive.InteractiveView;
import org.knime.core.node.interactive.ViewContent;
import org.knime.core.node.port.MetaPortInfo;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.port.flowvariable.FlowVariablePortObject;
import org.knime.core.node.port.flowvariable.FlowVariablePortObjectSpec;
import org.knime.core.node.property.hilite.HiLiteHandler;
import org.knime.core.node.util.CheckUtils;
import org.knime.core.node.util.NodeExecutionJobManagerPool;
import org.knime.core.node.wizard.WizardNodeLayoutInfo;
import org.knime.core.node.workflow.MetaNodeTemplateInformation.Role;
import org.knime.core.node.workflow.MetaNodeTemplateInformation.TemplateType;
import org.knime.core.node.workflow.NodeContainer.NodeContainerSettings.SplitType;
import org.knime.core.node.workflow.NodeMessage.Type;
import org.knime.core.node.workflow.NodePropertyChangedEvent.NodeProperty;
import org.knime.core.node.workflow.WorkflowManager.NodeModelFilter;
import org.knime.core.node.workflow.WorkflowPersistor.LoadResult;
import org.knime.core.node.workflow.WorkflowPersistor.NodeContainerTemplateLinkUpdateResult;
import org.knime.core.node.workflow.WorkflowPersistor.WorkflowPortTemplate;
import org.knime.core.node.workflow.execresult.NodeContainerExecutionResult;
import org.knime.core.node.workflow.execresult.NodeContainerExecutionStatus;
import org.knime.core.node.workflow.virtual.subnode.VirtualSubNodeExchange;
import org.knime.core.node.workflow.virtual.subnode.VirtualSubNodeInputNodeFactory;
import org.knime.core.node.workflow.virtual.subnode.VirtualSubNodeInputNodeModel;
import org.knime.core.node.workflow.virtual.subnode.VirtualSubNodeOutputNodeFactory;
import org.knime.core.node.workflow.virtual.subnode.VirtualSubNodeOutputNodeModel;
import org.knime.core.quickform.QuickFormRepresentation;
import org.knime.core.util.LockFailedException;
import org.knime.core.util.Pair;
import org.knime.core.util.ThreadPool;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Implementation of a {@link NodeContainer} holding a set of nodes via a {@link WorkflowManager}
 * and acting like a {@link NativeNodeContainer} to the outside.
 *
 * @noreference Not to be used by clients.
 * @author M. Berthold & B. Wiswedel
 * @since 2.9
 */
public final class SubNodeContainer extends SingleNodeContainer implements NodeContainerParent, NodeContainerTemplate {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(SubNodeContainer.class);

    /** Keeps outgoing information (specs, objects, HiLiteHandlers...). */
    private static final class Output {
        private final PortType m_type;
        private String m_name = "none";
        private PortObjectSpec m_spec;
        private PortObject m_object;
        private HiLiteHandler m_hiliteHdl;
        private String m_summary = "no summary";

        /** @param type Non null port type. */
        Output(final PortType type) {
            m_type = type;
        }
        boolean setSpec(final PortObjectSpec spec) {
            boolean differ = ObjectUtils.notEqual(m_spec, spec);
            m_spec = spec;
            return differ;
        }
        boolean setName(final String name) {
            boolean differ = ObjectUtils.notEqual(m_name, name);
            m_name = name;
            return differ;
        }
        boolean setObject(final PortObject object) {
            boolean differ = ObjectUtils.notEqual(m_object, object);
            m_object = object;
            return differ;
        }
        boolean setHiliteHdl(final HiLiteHandler hiliteHdl) {
            boolean differ = ObjectUtils.notEqual(m_hiliteHdl, hiliteHdl);
            m_hiliteHdl = hiliteHdl;
            return differ;
        }
        boolean setSummary(final String summary) {
            boolean differ = ObjectUtils.notEqual(m_summary, summary);
            m_summary = summary;
            return differ;
        }
        PortType getType() {
            return m_type;
        }
        String getName() {
            return m_name;
        }
        PortObjectSpec getSpec() {
            return m_spec;
        }
        PortObject getObject() {
            return m_object;
        }
        HiLiteHandler getHiliteHdl() {
            return m_hiliteHdl;
        }
        String getSummary() {
            return m_summary;
        }
        void clean() {
            m_spec = null;
            m_object = null;
            m_hiliteHdl = null;
            m_summary = null;
        }
        static PortType[] getPortTypesNoFlowVariablePort(final Output[] outputs) {
            PortType[] result = new PortType[outputs.length - 1];
            for (int i = 1; i < outputs.length; i++) {
                result[i - 1] = outputs[i].getType();
            }
            return result;
        }

    }

    private final WorkflowManager m_wfm;

    private NodeStateChangeListener m_wfmStateChangeListener;

    private NodeInPort[] m_inports;
    private NodeContainerOutPort[] m_outports;
    private Output[] m_outputs;

    private int m_virtualInNodeIDSuffix;
    private int m_virtualOutNodeIDSuffix;

    private FlowObjectStack m_incomingStack;
    private FlowObjectStack m_outgoingStack;

    /** Helper flag to avoid state transitions as part callbacks from the inner wfm. These are ignored when execution
     * is triggered via {@link #performExecuteNode(PortObject[])} or reset via {@link #performReset()}. */
    private boolean m_isPerformingActionCalledFromParent;

    /** Layout info for wizard nodes. */
    private Map<Integer, WizardNodeLayoutInfo> m_layoutInfo;

    private MetaNodeTemplateInformation m_templateInformation;

    /** Load workflow from persistor.
     *
     * @param parent ...
     * @param id ...
     * @param persistor ...
     */
    SubNodeContainer(final WorkflowManager parent, final NodeID id, final SubNodeContainerPersistor persistor) {
        super(parent, id, persistor.getMetaPersistor());
        WorkflowPersistor workflowPersistor = persistor.getWorkflowPersistor();
        m_wfm = new WorkflowManager(this, null, new NodeID(id, 0), workflowPersistor,
            parent.getGlobalTableRepository(), parent.getFileStoreHandlerRepository());

        WorkflowPortTemplate[] inPortTemplates = persistor.getInPortTemplates();
        WorkflowPortTemplate[] outPortTemplates = persistor.getOutPortTemplates();
        m_outports = new NodeContainerOutPort[outPortTemplates.length];
        m_outputs = new Output[outPortTemplates.length];
        for (int i = 0; i < outPortTemplates.length; i++) {
            WorkflowPortTemplate t = outPortTemplates[i];
            m_outputs[i] = new Output(t.getPortType());
            m_outputs[i].setName(t.getPortName());
            m_outports[i] = new NodeContainerOutPort(this, t.getPortType(), t.getPortIndex());
            m_outports[i].setPortName(t.getPortName());
        }
        m_inports = new NodeInPort[inPortTemplates.length];
        m_virtualInNodeIDSuffix = persistor.getVirtualInNodeIDSuffix();
        m_virtualOutNodeIDSuffix = persistor.getVirtualOutNodeIDSuffix();
        m_layoutInfo = persistor.getLayoutInfo();
        PortType[] inTypes = new PortType[inPortTemplates.length];
        for (int i = 0; i < inPortTemplates.length; i++) {
            inTypes[i] = inPortTemplates[i].getPortType();
            m_inports[i] = new NodeInPort(i, inTypes[i]);
        }
        m_templateInformation = persistor.getTemplateInformation();
    }

    /**
     * Create new SubNode from existing Metanode (=WorkflowManager).
     *
     * @param parent ...
     * @param id ...
     * @param content ...
     * @param name The name of the sub node
     */
    SubNodeContainer(final WorkflowManager parent, final NodeID id, final WorkflowManager content,
        final String name) {
        super(parent, id);
        // Create new, internal workflow manager:
        m_wfm = new WorkflowManager(this, null, new NodeID(id, 0), new PortType[]{}, new PortType[]{}, false,
                parent.getContext(), name, parent.getGlobalTableRepository(), parent.getFileStoreHandlerRepository());
        // and copy content
        WorkflowCopyContent c = new WorkflowCopyContent();
        c.setAnnotation(content.getWorkflowAnnotations().toArray(new WorkflowAnnotation[0]));
        c.setNodeIDs(content.getWorkflow().getNodeIDs().toArray(new NodeID[0]));
        c.setIncludeInOutConnections(false);
        WorkflowPersistor wp = content.copy(c);
        WorkflowCopyContent wcc = m_wfm.paste(wp);
        // create map of NodeIDs for quick lookup/search
        Collection<NodeContainer> ncs = content.getNodeContainers();
        NodeID[] orgIDs = new NodeID[ncs.size()];
        int j = 0;
        for (NodeContainer nc : ncs) {
            orgIDs[j] = nc.getID();
            j++;
        }
        NodeID[] newIDs = wcc.getNodeIDs();
        Map<NodeID, NodeID> oldIDsHash = new HashMap<NodeID, NodeID>();
        for (j = 0; j < orgIDs.length; j++) {
            oldIDsHash.put(orgIDs[j], newIDs[j]);
        }
        // initialize NodeContainer inports
        // (metanodes don't have hidden variable port 0, SingleNodeContainers do!)
        m_inports = new NodeInPort[content.getNrInPorts() + 1];
        PortType[] inTypes = new PortType[content.getNrInPorts()];
        for (int i = 0; i < content.getNrInPorts(); i++) {
            inTypes[i] = content.getInPort(i).getPortType();
            m_inports[i + 1] = new NodeInPort(i + 1, inTypes[i]);
        }
        m_inports[0] = new NodeInPort(0, FlowVariablePortObject.TYPE_OPTIONAL);
        // initialize NodeContainer outports
        // (metanodes don't have hidden variable port 0, SingleNodeContainers do!)
        m_outports = new NodeContainerOutPort[content.getNrOutPorts() + 1];
        PortType[] outTypes = new PortType[content.getNrOutPorts()];
        m_outputs = new Output[content.getNrOutPorts() + 1];
        for (int i = 0; i < content.getNrOutPorts(); i++) {
            outTypes[i] = content.getOutPort(i).getPortType();
            m_outputs[i + 1] = new Output(content.getOutPort(i).getPortType());
            m_outports[i + 1] = new NodeContainerOutPort(this, m_outputs[i + 1].getType(), i + 1);
        }
        m_outputs[0] = new Output(FlowVariablePortObject.TYPE);
        m_outports[0] = new NodeContainerOutPort(this, FlowVariablePortObject.TYPE, 0);
        Pair<int[], int[]> minMaxCoordinates = getMinMaxCoordinates();
        // add virtual in/out nodes and connect them
        NodeID inNodeID = addVirtualInNode(inTypes, minMaxCoordinates);
        for (ConnectionContainer cc : content.getWorkflow().getConnectionsBySource(content.getID())) {
            m_wfm.addConnection(inNodeID, cc.getSourcePort() + 1, oldIDsHash.get(cc.getDest()), cc.getDestPort());
        }
        m_virtualInNodeIDSuffix = inNodeID.getIndex();
        NodeID outNodeID = addVirtualOutNode(outTypes, getMinMaxCoordinates());
        for (ConnectionContainer cc : content.getWorkflow().getConnectionsByDest(content.getID())) {
            m_wfm.addConnection(oldIDsHash.get(cc.getSource()), cc.getSourcePort(), outNodeID, cc.getDestPort() + 1);
        }
        m_virtualOutNodeIDSuffix = outNodeID.getIndex();
        getVirtualInNodeModel().setSubNodeContainer(this);
        m_wfmStateChangeListener = createAndAddStateListener();
        setInternalState(m_wfm.getInternalState());
        m_templateInformation = MetaNodeTemplateInformation.NONE;
    }

    /** Adds new/empty instance of a virtual input node and returns its ID. */
    private NodeID addVirtualInNode(final PortType[] inTypes, final Pair<int[], int[]> minMaxCoordinates) {
        NodeID inNodeID = m_wfm.addNode(new VirtualSubNodeInputNodeFactory(this, inTypes));
        final NodeContainer inNodeNC = m_wfm.getNodeContainer(inNodeID);
        inNodeNC.setDeletable(false);

        int[] minCoordinates = minMaxCoordinates.getFirst();
        int[] maxCoordinates = minMaxCoordinates.getSecond();
        int x = minCoordinates[0] - 100;
        int y = (minCoordinates[1] + maxCoordinates[1]) / 2;
        inNodeNC.setUIInformation(new NodeUIInformation(x, y, 0, 0, true));
        return inNodeID;
    }

    /** Adds new/empty instance of a virtual output node and returns its ID. */
    private NodeID addVirtualOutNode(final PortType[] outTypes, final Pair<int[], int[]> minMaxCoordinates) {
        NodeID outNodeID = m_wfm.addNode(new VirtualSubNodeOutputNodeFactory(outTypes));
        final NodeContainer outNodeNC = m_wfm.getNodeContainer(outNodeID);
        outNodeNC.setDeletable(false);

        int[] minCoordinates = minMaxCoordinates.getFirst();
        int[] maxCoordinates = minMaxCoordinates.getSecond();
        int x = maxCoordinates[0] + 100;
        int y = (minCoordinates[1] + maxCoordinates[1]) / 2;
        outNodeNC.setUIInformation(new NodeUIInformation(x, y, 0, 0, true));
        return outNodeID;
    }

    /** Iterates all nodes and determines min and max x,y coordinates. Used to place virtual in & output nodes.
     * @return 1st: {minX, minY}, 2nd: {maxX, maxY} */
    private Pair<int[], int[]> getMinMaxCoordinates() {
        final Collection<NodeContainer> nodeContainers = m_wfm.getNodeContainers();
        final int nodeCount = nodeContainers.size();
        int xmin = nodeCount > 0 ? Integer.MAX_VALUE : 0;
        int ymin = nodeCount > 0 ? Integer.MAX_VALUE : 50;
        int xmax = nodeCount > 0 ? Integer.MIN_VALUE : 100;
        int ymax = nodeCount > 0 ? Integer.MIN_VALUE : 50;
        for (NodeContainer nc : nodeContainers) {
            NodeUIInformation uii = nc.getUIInformation();
            if (uii != null) {
                int[] bounds = uii.getBounds();
                if (bounds.length >= 2) {
                    xmin = Math.min(bounds[0], xmin);
                    ymin = Math.min(bounds[1], ymin);
                    xmax = Math.max(bounds[0], xmax);
                    ymax = Math.max(bounds[1], ymax);
                }
            }
        }
        return Pair.create(new int[] {xmin, ymin}, new int[] {xmax, ymax});
    }

    /** Creates listener, adds it to m_wfm and sets the class field. */
    private NodeStateChangeListener createAndAddStateListener() {
        NodeStateChangeListener listener = new NodeStateChangeListener() {
            @Override
            public void stateChanged(final NodeStateEvent state) {
                onWFMStateChange(state);
            }
        };
        m_wfm.addNodeStateChangeListener(listener);
        return listener;
    }

    /** Called by listener on inner workflow. */
    private void onWFMStateChange(final NodeStateEvent state) {
        if (!m_isPerformingActionCalledFromParent) {
            final WorkflowManager parent = getParent();
            assert Thread.holdsLock(parent.getWorkflowMutex()) : "Unsynchronized workflow state event";
            final InternalNodeContainerState oldState = getInternalState();
            final InternalNodeContainerState newState = state.getInternalNCState();
            final boolean gotReset = oldState.isExecuted() && !newState.isExecuted();
            if (gotReset) {
                // a contained node was reset -- trigger reset downstream
                parent.resetSuccessors(getID(), -1);
            }
            boolean outputChanged = setVirtualOutputIntoOutport(newState);
            setInternalState(newState);
            if (outputChanged) {
                parent.resetAndConfigureNodeAndSuccessors(getID(), false);
            } else {
                parent.checkForNodeStateChanges(true);
            }
        }
    }

    /* -------------------- Virtual node callbacks -------------- */

    /** Called from virtual input node when executed - in possibly executes nodes in the parent wfm and then
     * fetches the data from it.
     * @return the subnode data input (incl. mandatory flow var port object).
     * @throws ExecutionException any exception thrown while waiting for upstream nodes to finish execution. */
    public PortObject[] fetchInputDataFromParent() throws ExecutionException {
        return ThreadPool.currentPool().runInvisible(new Callable<PortObject[]>() {
            @Override
            public PortObject[] call() throws Exception {
                final WorkflowManager parent = getParent();
                // might be not yet or no longer in workflow (e.g. part of construction)
                if (parent.containsNodeContainer(getID())) {
                    PortObject[] results = new PortObject[getNrInPorts()];
                    parent.executePredecessorsAndWait(getID());
                    if (parent.assembleInputData(getID(), results)) {
                        return results;
                    }
                }
                return null;
            }
        });
    }

    /** Fetches input specs of subnode, including mandatory flow var port. Used by virtual sub node input during
     * configuration.
     * @return input specs from subnode (as available from connected outports).
     */
    public PortObjectSpec[] fetchInputSpecFromParent() {
        PortObjectSpec[] results = new PortObjectSpec[getNrInPorts()];
        final WorkflowManager parent = getParent();
        // might be not yet or no longer in workflow (e.g. part of construction)
        if (parent.containsNodeContainer(getID())) {
            parent.assembleInputSpecs(getID(), results);
        }
        return results;
    }

    /* -------------------- Subnode specific -------------- */

    /** @return the inportNodeModel */
    NativeNodeContainer getVirtualInNode() {
        return (NativeNodeContainer)m_wfm.getNodeContainer(getVirtualInNodeID());
    }

    /** @return the inportNodeModel */
    VirtualSubNodeInputNodeModel getVirtualInNodeModel() {
        return (VirtualSubNodeInputNodeModel)getVirtualInNode().getNodeModel();
    }

    /** @return the outportNodeModel */
    NativeNodeContainer getVirtualOutNode() {
        return (NativeNodeContainer)m_wfm.getNodeContainer(getVirtualOutNodeID());
    }

    /** @return the outportNodeModel */
    VirtualSubNodeOutputNodeModel getVirtualOutNodeModel() {
        return (VirtualSubNodeOutputNodeModel)getVirtualOutNode().getNodeModel();
    }

    /** Static utility node to retrieve the nodemodel of the virtual output node. It does the type casts etc.
     * @param wfm The wfm to query (inner wfm)
     * @param nodeIDSuffix the id of the output node (must exist)
     * @return the node model of the output node.
     */
    static VirtualSubNodeOutputNodeModel getVirtualOutputNodeModel(final WorkflowManager wfm, final int nodeIDSuffix) {
        NativeNodeContainer nnc = (NativeNodeContainer)wfm.getNodeContainer(new NodeID(wfm.getID(), nodeIDSuffix));
        return (VirtualSubNodeOutputNodeModel)nnc.getNodeModel();
    }

    /** Static utility node to retrieve the nodemodel of the virtual input node. It does the type casts etc.
     * @param wfm The wfm to query (inner wfm)
     * @param nodeIDSuffix the id of the input node (must exist)
     * @return the node model of the input node.
     */
    static VirtualSubNodeInputNodeModel getVirtualInputNodeModel(final WorkflowManager wfm, final int nodeIDSuffix) {
        NativeNodeContainer nnc = (NativeNodeContainer)wfm.getNodeContainer(new NodeID(wfm.getID(), nodeIDSuffix));
        return (VirtualSubNodeInputNodeModel)nnc.getNodeModel();
    }

    /** @return the inNodeID */
    public NodeID getVirtualInNodeID() {
        return new NodeID(m_wfm.getID(), m_virtualInNodeIDSuffix);
    }

    /** @return the outNodeID */
    public NodeID getVirtualOutNodeID() {
        return new NodeID(m_wfm.getID(), m_virtualOutNodeIDSuffix);
    }

    /* -------------------- NodeContainer info properties -------------- */

    @SuppressWarnings("rawtypes")
    /**
     * {@inheritDoc}
     */
    @Override
    public Element getXMLDescription() {
        VirtualSubNodeInputNodeModel inNode = getVirtualInNodeModel();
        VirtualSubNodeOutputNodeModel outNode = getVirtualOutNodeModel();
        String description = inNode.getSubNodeDescription();
        // Generate short text from long text
        String sDescription = shortenText(description.replace("\n", " "), 200);
        String[] inPortNames = inNode.getPortNames();
        String[] inPortDescriptions = inNode.getPortDescriptions();
        String[] outPortNames = outNode.getPortNames();
        String[] outPortDescriptions = outNode.getPortDescriptions();
        Map<NodeID, DialogNode> nodes = m_wfm.findNodes(DialogNode.class, false);
        List<String> optionNames = new ArrayList<String>();
        List<String> optionDescriptions = new ArrayList<String>();
        for (DialogNode dialogNode : nodes.values()) {
            DialogNodeRepresentation representation = dialogNode.getDialogRepresentation();
            if (representation instanceof QuickFormRepresentation) {
                optionNames.add(((QuickFormRepresentation)representation).getLabel());
                optionDescriptions.add(((QuickFormRepresentation)representation).getDescription());
            }
        }
        try {
            // Document
            Document doc =
                DocumentBuilderFactory.newInstance().newDocumentBuilder().getDOMImplementation()
                      .createDocument("http://knime.org/node2012", "knimeNode", null);
            // knimeNode
            Element knimeNode = doc.getDocumentElement();
            knimeNode.setAttribute("type", "Unknown");
            knimeNode.setAttribute("icon", "subnode.png");
            // name
            Element name = doc.createElement("name");
            knimeNode.appendChild(name);
            name.appendChild(doc.createTextNode(getName()));
            // shortDescription
            Element shortDescription = doc.createElement("shortDescription");
            knimeNode.appendChild(shortDescription);
            addText(shortDescription, sDescription);
            // fullDescription
            Element fullDescription = doc.createElement("fullDescription");
            knimeNode.appendChild(fullDescription);
            // intro
            Element intro = doc.createElement("intro");
            fullDescription.appendChild(intro);
            addText(intro, description);
            // option
            for (int i = 0; i < optionNames.size(); i++) {
                Element option = doc.createElement("option");
                fullDescription.appendChild(option);
                option.setAttribute("name", optionNames.get(i));
                addText(option, optionDescriptions.get(i));
            }
            // ports
            Element ports = doc.createElement("ports");
            knimeNode.appendChild(ports);
            // inPort
            for (int i = 0; i < inPortNames.length; i++) {
                Element inPort = doc.createElement("inPort");
                ports.appendChild(inPort);
                inPort.setAttribute("index", "" + i);
                inPort.setAttribute("name", inPortNames[i]);
                addText(inPort, inPortDescriptions[i]);
            }
            // outPort
            for (int i = 0; i < outPortNames.length; i++) {
                Element outPort = doc.createElement("outPort");
                ports.appendChild(outPort);
                outPort.setAttribute("index", "" + i);
                outPort.setAttribute("name", outPortNames[i]);
                addText(outPort, outPortDescriptions[i]);
            }
            return new NodeDescription27Proxy(doc).getXMLDescription();
        } catch (ParserConfigurationException | DOMException | XmlException e) {
            LOGGER.warn("Could not generate sub node description", e);
        }
        return null;
    }

    private void refreshPortNames() {
        String[] inPortNames = getVirtualInNodeModel().getPortNames();
        String[] outPortNames = getVirtualOutNodeModel().getPortNames();
        for (int i = 0; i < inPortNames.length; i++) {
            getInPort(i + 1).setPortName(inPortNames[i]);
        }
        for (int i = 0; i < outPortNames.length; i++) {
            getOutPort(i + 1).setPortName(outPortNames[i]);
        }
        notifyUIListeners(null);
    }

    private class RefreshPortNamesListener implements NodeStateChangeListener {
        @Override
        public void stateChanged(final NodeStateEvent state) {
            refreshPortNames();
        }
    }

    /**
     * Puts the text into the element while replacing new lines with <br /> elements.
     *
     * @param element The element to add to
     * @param text The text to add
     */
    private void addText(final Element element, final String text) {
        Document doc = element.getOwnerDocument();
        String[] splitText = text.split("\n");
        for (int i = 0; i < splitText.length; i++) {
            element.appendChild(doc.createTextNode(splitText[i]));
            if (i + 1 < splitText.length) {
                element.appendChild(doc.createElement("br"));
            }
        }
    }

    /**
     * @param text The text that should be shortened
     * @param maxLength The maximum length of the text
     * @return The shortened text cut of at the last complete word (if possible) and followed by ...
     */
    private String shortenText(final String text, final int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        // reserve last three characters for ...
        int actualMaxLength = maxLength >= 3 ? maxLength - 3 : 0;
        String shortenedText = text.substring(0, actualMaxLength);
        int lastIndex = Math.max(shortenedText.lastIndexOf(" "), shortenedText.lastIndexOf("\n"));
        if (lastIndex >= 0) {
            shortenedText = shortenedText.substring(0, lastIndex);
        }
        return shortenedText + "...";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public URL getIcon() {
        // TODO return useful Icons
        return SubNodeContainer.class.getResource("virtual/subnode/empty.png");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeType getType() {
        // TODO create and return matching icon
        return NodeType.Subnode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return m_wfm.getName();
    }

    /**
     * @param name The new name
     * @since 2.10
     */
    public void setName(final String name) {
        m_wfm.setName(name);
        setDirty();
        notifyNodePropertyChangedListener(NodeProperty.Name);
    }

    /* ------------------- Specific Interna ------------------- */

    /**
     * @return underlying workflow.
     */
    public WorkflowManager getWorkflowManager() {
        return m_wfm;
    }

    /* -------------------- Dialog Handling ------------------ */

    private NodeDialogPane m_nodeDialogPane;

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasDialog() {
        int c = NodeExecutionJobManagerPool.getNumberOfJobManagersFactories();
        return c > 1 || m_wfm.findNodes(DialogNode.class, true).size() > 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("rawtypes")
    NodeDialogPane getDialogPaneWithSettings(final PortObjectSpec[] inSpecs, final PortObject[] inData)
        throws NotConfigurableException {
        NodeDialogPane dialogPane = getDialogPane();
        // find all dialog nodes and update subnode dialog
        Map<NodeID, MetaNodeDialogNode> nodes = m_wfm.findNodes(MetaNodeDialogNode.class,
            new NodeModelFilter<MetaNodeDialogNode>() {
            @Override
            public boolean include(final MetaNodeDialogNode nodeModel) {
                return nodeModel instanceof DialogNode && !((DialogNode)nodeModel).isHideInDialog();
            }
        }, false);
        ((MetaNodeDialogPane)dialogPane).setQuickformNodes(nodes);
        NodeSettings settings = new NodeSettings("subnode_settings");
        saveSettings(settings);
        Node.invokeDialogInternalLoad(dialogPane, settings, inSpecs, inData,
            getFlowObjectStack(), new CredentialsProvider(this, m_wfm.getCredentialsStore()),
            getParent().isWriteProtected());
        return dialogPane;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    NodeDialogPane getDialogPane() {
        if (m_nodeDialogPane == null) {
            if (hasDialog()) {
                // create sub node dialog with dialog nodes
                m_nodeDialogPane = new MetaNodeDialogPane(true);
                // job managers tab
                if (NodeExecutionJobManagerPool.getNumberOfJobManagersFactories() > 1) {
                    // TODO: set the SplitType depending on the nodemodel
                    SplitType splitType = SplitType.USER;
                    m_nodeDialogPane.addJobMgrTab(splitType);
                }
                Node.addMiscTab(m_nodeDialogPane);
            } else {
                throw new IllegalStateException("Workflow has no dialog");
            }
        }
        return m_nodeDialogPane;
    }

    /* -------------------- Views ------------------ */

    // TODO: enable view handling!
    /**
     * {@inheritDoc}
     */
    @Override
    public int getNrNodeViews() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getNodeViewName(final int i) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractNodeView<NodeModel> getNodeView(final int i) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasInteractiveView() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasInteractiveWebView() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getInteractiveViewName() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <V extends AbstractNodeView<?> & InteractiveView<?, ? extends ViewContent, ? extends ViewContent>> V
        getInteractiveView() {
        return null;
    }

    /** This is ambigious. A sub node currently doesn't support ExecutionEnvironments (in fact NativeNodeContainer
     * doesn't either). This method is overridden to avoid null checks in the super class.
     * {@inheritDoc} */
    @Override
    protected void setExecutionEnvironment(final ExecutionEnvironment exEnv) {
        // Deliberately empty
        // Super class implementation checks null assignment of the field variable - but a sub node can make many
        // state transitions from configured -> configured  -> ... which conflicts with the state checks in the super
        // implementation
    }

    /* -------------- Configuration/Execution ------------------- */

    /** {@inheritDoc} */
    @Override
    void performReset() {
        setNodeMessage(NodeMessage.NONE);
        m_isPerformingActionCalledFromParent = true;
        try {
            m_wfm.resetAllNodesInWFM();
            setVirtualOutputIntoOutport(m_wfm.getInternalState());
        } finally {
            m_isPerformingActionCalledFromParent = false;
        }
    }

    /** {@inheritDoc} */
    @Override
    boolean performConfigure(final PortObjectSpec[] rawInSpecs, final NodeConfigureHelper nch) {
        assert rawInSpecs.length == m_inports.length;
        m_isPerformingActionCalledFromParent = true;
        setNodeMessage(null);
        try {
            if (nch != null) {
                try {
                    nch.preConfigure();
                } catch (InvalidSettingsException ise) {
                    LOGGER.warn(ise.getMessage(), ise);
                    setNodeMessage(new NodeMessage(NodeMessage.Type.WARNING, ise.getMessage()));
                    return false;
                }
            }
            // and launch a configure on entire sub workflow
            m_wfm.reconfigureAllNodesOnlyInThisWFM();
            final InternalNodeContainerState internalState = m_wfm.getInternalState();
            setVirtualOutputIntoOutport(internalState);
            setInternalState(internalState);
            if (nch != null) {
                try {
                    nch.postConfigure(rawInSpecs, null);
                } catch (InvalidSettingsException ise) {
                    LOGGER.warn(ise.getMessage(), ise);
                    setNodeMessage(new NodeMessage(NodeMessage.Type.WARNING, ise.getMessage()));
                    return false;
                }
            }
            return internalState.isConfigured();
        } finally {
            m_isPerformingActionCalledFromParent = false;
        }
    }

    /** {@inheritDoc} */
    @Override
    boolean performStateTransitionQUEUED() {
        // theoretically we can come from any state into queued state, e.g. this node can be marked for
        // execution (which is the most likely state when run from the outer workflow) and then something is done
        // internally that causes an internal checkForNodeStateChanges.
        setInternalState(InternalNodeContainerState.CONFIGURED_QUEUED);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    boolean performStateTransitionPREEXECUTE() {
        synchronized (m_nodeMutex) {
            getProgressMonitor().reset();
            switch (getInternalState()) {
            case EXECUTED_QUEUED:
            case CONFIGURED_QUEUED:
                setInternalState(InternalNodeContainerState.PREEXECUTE);
                return true;
            default:
                // ignore any other state: other states indicate that the node
                // was canceled before it is actually run
                // (this method is called from a worker thread, whereas cancel
                // typically from the UI thread)
                if (!Thread.currentThread().isInterrupted()) {
                    LOGGER.debug("Execution of node " + getNameWithID()
                            + " was probably canceled (node is " + getInternalState()
                            + " during 'preexecute') but calling thread is not"
                            + " interrupted");
                }
                return false;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    void performStateTransitionEXECUTING() {
        synchronized (m_nodeMutex) {
            switch (getInternalState()) {
            case PREEXECUTE:
                if (findJobManager() instanceof ThreadNodeExecutionJobManager) {
                    setInternalState(InternalNodeContainerState.EXECUTING);
                } else {
                    setInternalState(InternalNodeContainerState.EXECUTINGREMOTELY);
                }
                break;
            default:
                throwIllegalStateException();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    void performStateTransitionPOSTEXECUTE() {
        synchronized (m_nodeMutex) {
            switch (getInternalState()) {
            case PREEXECUTE: // in case of errors, e.g. flow stack problems
                             // encountered during doBeforeExecution
            case EXECUTING:
            case EXECUTINGREMOTELY:
                setInternalState(InternalNodeContainerState.POSTEXECUTE);
                break;
            default:
                throwIllegalStateException();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    void performStateTransitionEXECUTED(
            final NodeContainerExecutionStatus status) {
        synchronized (m_nodeMutex) {
            switch (getInternalState()) {
            case POSTEXECUTE:
                if (status.isSuccess()) {
                    setInternalState(InternalNodeContainerState.EXECUTED);
                } else {
                    // node will be configured in doAfterExecute.
                    // for now we assume complete failure and clean up (reset)
                    // We do keep the message, though.
                    NodeMessage oldMessage = getNodeMessage();
                    NodeContext.pushContext(this);
                    try {
                        performReset();
                    } finally {
                        NodeContext.removeLastContext();
                    }
                    setNodeMessage(oldMessage);
                    setInternalState(InternalNodeContainerState.IDLE);
                }
                setExecutionJob(null);
                break;
            default:
                throwIllegalStateException();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeContainerExecutionStatus performExecuteNode(final PortObject[] rawInObjects) {
        setNodeMessage(NodeMessage.NONE);
        assert rawInObjects.length == m_inports.length;
        m_isPerformingActionCalledFromParent = true;
        try {
            // and launch execute on entire sub workflow
            m_wfm.executeAll();
            boolean isCanceled = false;
            try {
                m_wfm.waitWhileInExecution(-1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                m_wfm.cancelExecution();
                isCanceled = true;
            }
            final InternalNodeContainerState internalState = m_wfm.getInternalState();
            boolean allExecuted = internalState.isExecuted();
            if (allExecuted) {
                setVirtualOutputIntoOutport(internalState);
            } else if (isCanceled) {
                setNodeMessage(new NodeMessage(Type.WARNING, "Execution canceled"));
            } else {
                setNodeMessage(new NodeMessage(Type.ERROR, "Not all contained nodes are executed:\n"
                        + m_wfm.printNodeSummary(m_wfm.getID(), 0)));
            }
            return allExecuted ? NodeContainerExecutionStatus.SUCCESS : NodeContainerExecutionStatus.FAILURE;
        } finally {
            m_isPerformingActionCalledFromParent = false;
        }
    }

    /** Copies data from virtual output node into m_outputs, notifies state listeners if not
     * processing call from parent.
     * @param newState TODO*/
    @SuppressWarnings("null")
    private boolean setVirtualOutputIntoOutport(final InternalNodeContainerState newState) {
        // retrieve results and copy to outports
        final VirtualSubNodeOutputNodeModel virtualOutNodeModel = getVirtualOutNodeModel();
        final VirtualSubNodeExchange outputExchange = virtualOutNodeModel.getOutputExchange();

        boolean publishObjects = outputExchange != null && newState.isExecuted();
        boolean publishSpecs = outputExchange != null
                && (newState.isConfigured() || newState.isExecuted() || newState.isExecutionInProgress());
        boolean changed = false;
        PortObjectSpec[] outSpecs = publishSpecs ? outputExchange.getPortSpecs() : null;
        PortObject[] outObjects = publishObjects ? outputExchange.getPortObjects() : null;
        for (int i = 1; i < m_outputs.length; i++) {
            changed = m_outputs[i].setSpec(outSpecs == null ? null : outSpecs[i - 1]) || changed;
            changed = m_outputs[i].setObject(outObjects == null ? null : outObjects[i - 1]) || changed;
        }
        changed = m_outputs[0].setSpec(publishSpecs ? FlowVariablePortObjectSpec.INSTANCE : null) || changed;
        changed = m_outputs[0].setObject(publishObjects ? FlowVariablePortObject.INSTANCE : null) || changed;
        if (changed && !m_isPerformingActionCalledFromParent) {
            notifyStateChangeListeners(new NodeStateEvent(this)); // updates port views
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void cleanup() {
        super.cleanup();
        getVirtualInNodeModel().setSubNodeContainer(null);
        m_wfm.removeNodeStateChangeListener(m_wfmStateChangeListener);
        m_wfm.cleanup();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExecutionContext createExecutionContext() {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeContainerExecutionResult createExecutionResult(final ExecutionMonitor exec)
            throws CanceledExecutionException {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------- Ports and related stuff --------------- */

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNrInPorts() {
        return m_inports.length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeInPort getInPort(final int index) {
        return m_inports[index];
    }

    /**
     * @param portTypes Types of the new ports
     * @since 2.10
     */
    public void setInPorts(final PortType[] portTypes) {
        m_inports = new NodeInPort[portTypes.length + 1];
        for (int i = 0; i < portTypes.length; i++) {
            m_inports[i + 1] = new NodeInPort(i + 1, portTypes[i]);
        }
        NodeContainer oldVNode = m_wfm.getNodeContainer(getVirtualInNodeID());
        m_inports[0] = new NodeInPort(0, FlowVariablePortObject.TYPE_OPTIONAL);
        NodeSettings settings = new NodeSettings("node settings");
        try {
            m_wfm.saveNodeSettings(oldVNode.getID(), settings);
        } catch (InvalidSettingsException e) {
            // no valid settings available, skip
        }
        m_virtualInNodeIDSuffix = m_wfm.addNode(new VirtualSubNodeInputNodeFactory(this, portTypes)).getIndex();
        NodeContainer newVNode = m_wfm.getNodeContainer(getVirtualInNodeID());
        newVNode.setUIInformation(oldVNode.getUIInformation());
        // copy settings from old to new node
        try {
            m_wfm.loadNodeSettings(newVNode.getID(), settings);
        } catch (InvalidSettingsException e) {
            // ignore
        }
        oldVNode.setDeletable(true);
        m_wfm.removeNode(oldVNode.getID());
        getInPort(0).setPortName("Variable Inport");
        newVNode.addNodeStateChangeListener(new RefreshPortNamesListener());
        refreshPortNames();
        m_wfm.setDirty();
        setDirty();
        notifyNodePropertyChangedListener(NodeProperty.MetaNodePorts);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNrOutPorts() {
        return m_outports.length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeOutPort getOutPort(final int index) {
        return m_outports[index];
    }

    /**
     * @param portTypes Types of the new ports
     * @since 2.10
     */
    public void setOutPorts(final PortType[] portTypes) {
        m_outputs = new Output[portTypes.length + 1];
        m_outports = new NodeContainerOutPort[portTypes.length + 1];
        for (int i = 0; i < portTypes.length; i++) {
            m_outputs[i + 1] = new Output(portTypes[i]);
            m_outports[i + 1] = new NodeContainerOutPort(this, portTypes[i], i + 1);
        }
        m_outputs[0] = new Output(FlowVariablePortObject.TYPE);
        m_outports[0] = new NodeContainerOutPort(this, FlowVariablePortObject.TYPE, 0);
        NodeContainer oldVNode = m_wfm.getNodeContainer(getVirtualOutNodeID());
        NodeSettings settings = new NodeSettings("node settings");
        try {
            m_wfm.saveNodeSettings(oldVNode.getID(), settings);
        } catch (InvalidSettingsException e) {
            // no valid settings available, skip
        }
        m_virtualOutNodeIDSuffix = m_wfm.addNode(new VirtualSubNodeOutputNodeFactory(portTypes)).getIndex();
        NodeContainer newVNode = m_wfm.getNodeContainer(getVirtualOutNodeID());
        newVNode.setUIInformation(oldVNode.getUIInformation());
        // copy settings from old to new node
        try {
            m_wfm.loadNodeSettings(newVNode.getID(), settings);
        } catch (InvalidSettingsException e) {
            // ignore
        }
        oldVNode.setDeletable(true);
        m_wfm.removeNode(oldVNode.getID());
        getOutPort(0).setPortName("Variable Outport");
        newVNode.addNodeStateChangeListener(new RefreshPortNamesListener());
        refreshPortNames();
        m_wfm.setDirty();
        setDirty();
        notifyNodePropertyChangedListener(NodeProperty.MetaNodePorts);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void cleanOutPorts(final boolean isLoopRestart) {
        for (Output o : m_outputs) {
            o.clean();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PortType getOutputType(final int portIndex) {
        return m_outputs[portIndex].getType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PortObjectSpec getOutputSpec(final int portIndex) {
        return m_outputs[portIndex].getSpec();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PortObject getOutputObject(final int portIndex) {
        return m_outputs[portIndex].getObject();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getOutputObjectSummary(final int portIndex) {
        return "SubNode Output: " + m_outputs[portIndex].getSummary();
    }

    /* ------------- HiLite Support ---------------- */

    /* TODO: enable if this ever makes sense. */

    /**
     * {@inheritDoc}
     */
    @Override
    void setInHiLiteHandler(final int index, final HiLiteHandler hdl) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HiLiteHandler getOutputHiLiteHandler(final int portIndex) {
        return null;
    }

    /* ------------------ Load&Save ------------------------- */

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("rawtypes")
    @Override
    boolean performAreModelSettingsValid(final NodeSettingsRO modelSettings) {
        Map<NodeID, DialogNode> nodes = m_wfm.findNodes(DialogNode.class, false);
        for (Map.Entry<NodeID, DialogNode> entry : nodes.entrySet()) {
            NodeID id = entry.getKey();
            String nodeID = Integer.toString(id.getIndex());
            if (modelSettings.containsKey(nodeID)) {
                try {
                    NodeSettingsRO conf = modelSettings.getNodeSettings(nodeID);
                    DialogNode node = entry.getValue();
                    final DialogNodeValue emptyDialogValue = node.createEmptyDialogValue();
                    emptyDialogValue.validateSettings(conf);
                } catch (InvalidSettingsException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("rawtypes")
    void performLoadModelSettingsFrom(final NodeSettingsRO modelSettings) throws InvalidSettingsException {
        assert Thread.holdsLock(getWorkflowMutex());
        synchronized (m_nodeMutex) {
            // check state of contained WFM as state of this Subnode may already be "MARKED".
            if (m_wfm.getInternalState().isExecutionInProgress()) {
                throw new IllegalStateException("Cannot load settings as the subnode is currently executing");
            }
            Map<NodeID, DialogNode> nodes = m_wfm.findNodes(DialogNode.class, false);
            // contains all nodes that have new value (different to previous value, even if null now).
            Map<NodeID, DialogNodeValue> newDialogValueMap = new HashMap<>();

            // iterate all dialog nodes and determine those that have a new dialog value. Just load the value
            // but do not set it yet in order to verify/load all settings before applying them
            for (Map.Entry<NodeID, DialogNode> entry : nodes.entrySet()) {
                final NodeID id = entry.getKey();
                final DialogNode node = entry.getValue();
                final String nodeIDSuffix = Integer.toString(id.getIndex());

                // the old/previously set value in the node
                final DialogNodeValue oldDialogValue = node.getDialogValue();
                final NodeSettings oldDialogValueSettings;
                if (oldDialogValue != null) {
                    oldDialogValueSettings = new NodeSettings(nodeIDSuffix);
                    oldDialogValue.saveToNodeSettings(oldDialogValueSettings);
                } else {
                    oldDialogValueSettings = null;
                }
                final NodeSettingsRO newDialogValueSettings = modelSettings.containsKey(nodeIDSuffix)
                        ? modelSettings.getNodeSettings(nodeIDSuffix) : null;

                // only apply if different to previous value. Fall back to equality on settings object ass
                // #equals on DialogNodeValue might not be implemented.
                if (ObjectUtils.notEqual(newDialogValueSettings, oldDialogValueSettings)) {
                    final DialogNodeValue newDialogValue;
                    if (newDialogValueSettings != null) {
                        newDialogValue = node.createEmptyDialogValue();
                        try {
                            newDialogValue.loadFromNodeSettings(newDialogValueSettings);
                        } catch (InvalidSettingsException e) {
                            throw new InvalidSettingsException(String.format(
                                "Cannot load dialog value for node \"%s\": %s",
                                ((NodeContainer)node).getNameWithID(), e.getMessage()), e);
                        }
                    } else {
                        newDialogValue = null;
                    }
                    newDialogValueMap.put(id, newDialogValue);
                }
            }

            // apply all new dialog values and reset/configure those nodes with modified config.
            for (Map.Entry<NodeID, DialogNodeValue> modifiedNodesEntry : newDialogValueMap.entrySet()) {
                final DialogNode node = nodes.get(modifiedNodesEntry.getKey());
                node.setDialogValue(modifiedNodesEntry.getValue());
                m_wfm.resetAndConfigureNode(modifiedNodesEntry.getKey());
            }

        }
    }

    /** Callback from persistor. */
     void postLoadWFM() {
         getVirtualInNodeModel().setSubNodeContainer(this);
    }

    /** {@inheritDoc} */
    @Override
    WorkflowCopyContent performLoadContent(final SingleNodeContainerPersistor nodePersistor,
        final Map<Integer, BufferedDataTable> tblRep, final FlowObjectStack inStack, final ExecutionMonitor exec,
        final LoadResult loadResult, final boolean preserveNodeMessage) throws CanceledExecutionException {
        SubNodeContainerPersistor subNodePersistor = (SubNodeContainerPersistor)nodePersistor;
        WorkflowPersistor workflowPersistor = subNodePersistor.getWorkflowPersistor();
        // TODO pass in a filter input stack
        m_wfm.loadContent(workflowPersistor, tblRep, inStack, exec, loadResult, preserveNodeMessage);
        checkInOutNodesAfterLoad(subNodePersistor, loadResult);
        // put data input output node if it was executed;
        final NativeNodeContainer virtualOutNode = getVirtualOutNode();
        if (virtualOutNode.getInternalState().isExecuted()) {
            VirtualSubNodeOutputNodeModel outNodeModel = getVirtualOutNodeModel();
            PortObject[] outputData = new PortObject[virtualOutNode.getNrInPorts()];
            m_wfm.assembleInputData(getVirtualOutNodeID(), outputData);
            outNodeModel.postLoadExecute(ArrayUtils.removeAll(outputData, 0));
        }
        setVirtualOutputIntoOutport(m_wfm.getInternalState());
        m_wfmStateChangeListener = createAndAddStateListener();
        getInPort(0).setPortName("Variable Inport");
        getOutPort(0).setPortName("Variable Outport");
        getVirtualInNode().addNodeStateChangeListener(new RefreshPortNamesListener());
        getVirtualOutNode().addNodeStateChangeListener(new RefreshPortNamesListener());
        refreshPortNames();
        return null;
    }

    /** Fixes in- and output nodes after loading (in case they don't exist or have errors). */
    private void checkInOutNodesAfterLoad(
        final SubNodeContainerPersistor subNodePersistor, final LoadResult loadResult) {
        /* Fix output node */
        NodeID virtualOutID = getVirtualOutNodeID();
        String error = null;                  // non null in case not is not present of of wrong type
        NodeSettings outputSettings = null;   // settings of previous node if present or null
        Pair<int[], int[]> minMaxCoordinates; // assigned with node insertion, used for node placement
        if (m_wfm.containsNodeContainer(virtualOutID)) {
            NodeContainer virtualOutNC = m_wfm.getNodeContainer(virtualOutID);
            if (virtualOutNC instanceof NativeNodeContainer) {
                NodeModel virtualOutModel = ((NativeNodeContainer)virtualOutNC).getNodeModel();
                if (!(virtualOutModel instanceof VirtualSubNodeOutputNodeModel)) {
                    // this is very likely a missing node (placeholder)
                    error = String.format("Virtual output node is not of expected type (expected %s, actual %s)",
                        VirtualSubNodeOutputNodeModel.class.getName(), virtualOutModel.getClass().getName());
                    try {
                        NodeSettings temp = new NodeSettings("temp");
                        m_wfm.saveNodeSettings(virtualOutID, temp);
                        outputSettings = temp;
                    } catch (InvalidSettingsException ise) {
                        // silently ignore; this is minor given that the node is not there.
                    }
                }
            } else {
                error = String.format("Virtual output node with ID %s is not a native node", virtualOutID);
            }
        } else {
            error = String.format("Virtual output node with ID %s does not exist", virtualOutID);
        }

        if (error != null) {
            minMaxCoordinates = getMinMaxCoordinates();
            m_virtualOutNodeIDSuffix = addVirtualOutNode(
                Output.getPortTypesNoFlowVariablePort(m_outputs), minMaxCoordinates).getIndex();
            error = error.concat(String.format(" - creating new instance (ID %s)", m_virtualOutNodeIDSuffix));
            loadResult.addError(error);
            if (outputSettings != null) {
                try {
                    m_wfm.loadNodeSettings(getVirtualOutNodeID(), outputSettings);
                } catch (InvalidSettingsException e) {
                    // again, ignore as the node was missing, which is much more critical
                }
            }
        }

        /* Fix input node */
        NodeID virtualInID = getVirtualInNodeID();
        error = null;                         // non null in case not is not present of of wrong type
        NodeSettings inputSettings = null;    // settings of previous node if present or null
        if (m_wfm.containsNodeContainer(virtualInID)) {
            NodeContainer virtualInNC = m_wfm.getNodeContainer(virtualInID);
            if (virtualInNC instanceof NativeNodeContainer) {
                NodeModel virtualInModel = ((NativeNodeContainer)virtualInNC).getNodeModel();
                if (!(virtualInModel instanceof VirtualSubNodeInputNodeModel)) {
                    // this is very likely a missing node (placeholder)
                    error = String.format("Virtual input node is not of expected type (expected %s, actual %s)",
                        VirtualSubNodeInputNodeModel.class.getName(), virtualInModel.getClass().getName());
                    try {
                        NodeSettings temp = new NodeSettings("temp");
                        m_wfm.saveNodeSettings(virtualInID, temp);
                        inputSettings = temp;
                    } catch (InvalidSettingsException ise) {
                        // silently ignore; this is minor given that the node is not there.
                    }
                }
            } else {
                error = String.format("Virtual input node with ID %s is not a native node", virtualInID);
            }
        } else {
            error = String.format("Virtual input node with ID %s does not exist", virtualInID);
        }

        if (error != null) {
            minMaxCoordinates = getMinMaxCoordinates();
            PortType[] inportTypes = new PortType[getNrInPorts() - 1]; // skip flow var port
            for (int i = 1; i < getNrInPorts(); i++) {
                inportTypes[i - 1] = getInPort(i).getPortType();
            }
            m_virtualInNodeIDSuffix = addVirtualInNode(inportTypes, minMaxCoordinates).getIndex();
            error = error.concat(String.format(" - creating new instance (ID %s)", m_virtualInNodeIDSuffix));
            loadResult.addError(error);
            if (inputSettings != null) {
                try {
                    m_wfm.loadNodeSettings(getVirtualInNodeID(), inputSettings);
                } catch (InvalidSettingsException e) {
                    // again, ignore as the node was missing, which is much more critical
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("rawtypes")
    void performSaveModelSettingsTo(final NodeSettings modelSettings) {
        Map<NodeID, DialogNode> nodes = m_wfm.findNodes(DialogNode.class, false);
        for (Map.Entry<NodeID, DialogNode> entry : nodes.entrySet()) {
            String nodeID = Integer.toString(entry.getKey().getIndex());
            final DialogNode dialogNode = entry.getValue();
            final DialogNodeValue dialogValue = dialogNode.getDialogValue();
            if (dialogValue != null) {
                NodeSettingsWO subSettings = modelSettings.addNodeSettings(nodeID);
                dialogValue.saveToNodeSettings(subSettings);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected NodeContainerPersistor getCopyPersistor(final HashMap<Integer, ContainerTable> tableRep,
        final FileStoreHandlerRepository fileStoreHandlerRepository, final boolean preserveDeletableFlags,
        final boolean isUndoableDeleteCommand) {
        return new CopySubNodeContainerPersistor(this,
            tableRep, fileStoreHandlerRepository, preserveDeletableFlags, isUndoableDeleteCommand);
    }

    /* -------------------- Credentials/Stacks ------------------ */

    /**
     * {@inheritDoc}
     */
    @Override
    void performSetCredentialsProvider(final CredentialsProvider cp) {
        // TODO needed once we want to support workflow credentials
    }

    /**
     * {@inheritDoc}
     */
    @Override
    CredentialsProvider getCredentialsProvider() {
        // TODO needed once we want to support workflow credentials
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void setFlowObjectStack(final FlowObjectStack st, final FlowObjectStack outgoingStack) {
        m_incomingStack = st;
        m_outgoingStack = outgoingStack;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FlowObjectStack getFlowObjectStack() {
        return m_incomingStack;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FlowObjectStack getOutgoingFlowObjectStack() {
        return m_outgoingStack;
    }

    /* -------------- Layouting --------- */

    /**
     * @return the layoutInfo
     * @since 2.10
     */
    public Map<Integer, WizardNodeLayoutInfo> getLayoutInfo() {
        if (m_layoutInfo == null) {
            m_layoutInfo = new HashMap<Integer, WizardNodeLayoutInfo>();
        }
        return m_layoutInfo;
    }

    /**
     * @param layoutInfo the layoutInfo to set
     * @since 2.10
     */
    public void setLayoutInfo(final Map<Integer, WizardNodeLayoutInfo> layoutInfo) {
        if (ObjectUtils.notEqual(m_layoutInfo, layoutInfo)) {
            m_layoutInfo = layoutInfo;
            setDirty();
        }
    }

    /* -------------- SingleNodeContainer methods without meaningful equivalent --------- */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isModelCompatibleTo(final Class<?> nodeModelClass) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInactive() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInactiveBranchConsumer() {
        return false;
    }

    /** Implementation of {@link WorkflowManager#getSubnodeInputPortInfo(NodeID)}.
     * @return ...
     */
    MetaPortInfo[] getInputPortInfo() {
        Workflow wfmFlow = m_wfm.getWorkflow();
        NodeContainer inNode = m_wfm.getNodeContainer(getVirtualInNodeID());

        List<MetaPortInfo> result = new ArrayList<MetaPortInfo>(inNode.getNrOutPorts());
        for (int i = 0; i < inNode.getNrOutPorts(); i++) {
            int insideCount = 0;
            for (ConnectionContainer cc : wfmFlow.getConnectionsBySource(
                    getVirtualInNodeID())) {
                if (cc.getSourcePort() == i) {
                    // could also be a through connection
                    insideCount += 1;
                }
            }
            boolean hasOutsideConnection = false;
            for (ConnectionContainer outCC : getParent().getWorkflow().getConnectionsByDest(getID())) {
                if (outCC.getDestPort() == i) {
                    hasOutsideConnection = true;
                    break;
                }
            }
            String message;
            boolean isConnected;
            PortType portType = inNode.getOutPort(i).getPortType();
            if (hasOutsideConnection || insideCount > 0) {
                isConnected = true;
                if (hasOutsideConnection && insideCount > 0) {
                    message = "Connected to one upstream node and "
                        + insideCount + " downstream node(s)";
                } else if (hasOutsideConnection) {
                    message = "Connected to one upstream node";
                } else {
                    message = "Connected to " + insideCount + " downstream node(s)";
                }
            } else {
                isConnected = false;
                message = null;
            }
            result.add(new MetaPortInfo(portType, isConnected, message, i));
        }
        return result.toArray(new MetaPortInfo[result.size()]);
    }

    /** Implementation of {@link WorkflowManager#getSubnodeOutputPortInfo(NodeID)}.
     * @return ...
     */
    MetaPortInfo[] getOutputPortInfo() {
        Workflow wfmFlow = m_wfm.getWorkflow();
        NodeContainer outNode = m_wfm.getNodeContainer(getVirtualOutNodeID());

        List<MetaPortInfo> result = new ArrayList<MetaPortInfo>(outNode.getNrInPorts());
        for (int i = 0; i < outNode.getNrInPorts(); i++) {
            boolean hasInsideConnection = false;
            for (ConnectionContainer cc : wfmFlow.getConnectionsByDest(getVirtualOutNodeID())) {
                if (cc.getDestPort() == i) {
                    hasInsideConnection = true;
                    break;
                }
            }
            int outsideCount = 0;
            for (ConnectionContainer outCC : getParent().getWorkflow().getConnectionsBySource(getID())) {
                if (outCC.getSourcePort() == i) {
                    outsideCount += 1;
                }
            }
            String message;
            boolean isConnected;
            PortType portType = outNode.getInPort(i).getPortType();
            if (hasInsideConnection || outsideCount > 0) {
                isConnected = true;
                if (hasInsideConnection && outsideCount > 0) {
                    message = "Connected to one upstream node and " + outsideCount + " downstream node(s)";
                } else if (hasInsideConnection) {
                    // could also be a through conn but we ignore here
                    message = "Connected to one upstream node";
                } else {
                    message = "Connected to " + outsideCount + " downstream node(s)";
                }
            } else {
                isConnected = false;
                message = null;
            }
            result.add(new MetaPortInfo(portType, isConnected, message, i));
        }
        return result.toArray(new MetaPortInfo[result.size()]);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean internalIsDirty() {
        return m_wfm.isDirty();
    }

    /* -------------- NodeContainerParent methods ---------------- */

    /** {@inheritDoc}*/
    @Override
    public Object getWorkflowMutex() {
        return getParent().getWorkflowMutex();
    }

    /** {@inheritDoc} */
    @Override
    public boolean canResetContainedNodes() {
        return getParent().canResetSuccessors(getID());
    }

    /** {@inheritDoc} */
    @Override
    public boolean isWriteProtected() {
        return getParent().isWriteProtected();
    }

    /** {@inheritDoc} */
    @Override
    public void pushWorkflowVariablesOnStack(final FlowObjectStack sos) {
        // TODO - this receives the variables from the parent.
    }

    /** {@inheritDoc} */
    @Override
    public final String getCipherFileName(final String fileName) {
        return WorkflowCipher.getCipherFileName(this, fileName);
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowCipher getWorkflowCipher() {
        return getDirectNCParent().getWorkflowCipher();
    }

    /** {@inheritDoc} */
    @Override
    public final OutputStream cipherOutput(final OutputStream out) throws IOException {
        return getDirectNCParent().cipherOutput(out);
    }

    /**
     * {@inheritDoc}
     * @since 2.10
     */
    @Override
    public WorkflowManager getProjectWFM() {
        return getDirectNCParent().getProjectWFM();
    }

    /* -------- template handling ----- */

    /** @return the templateInformation
     * @since 2.10*/
    @Override
    public MetaNodeTemplateInformation getTemplateInformation() {
        return m_templateInformation;
    }

    /** {@inheritDoc} */
    @Override
    public void setTemplateInformation(final MetaNodeTemplateInformation tI) {
        CheckUtils.checkArgumentNotNull(tI, "Argument must not be null.");
        CheckUtils.checkArgument(!Role.Template.equals(tI.getRole())
            || TemplateType.SubNode.equals(tI.getNodeContainerTemplateType()),
            "Template type expected to be subnode: %s", tI.getNodeContainerTemplateType());
        m_templateInformation = tI;
        notifyNodePropertyChangedListener(NodeProperty.TemplateConnection);
        setDirty();
    }

    /** {@inheritDoc} */
    @Override
    public MetaNodeTemplateInformation saveAsTemplate(final File directory, final ExecutionMonitor exec)
        throws IOException, CanceledExecutionException, LockFailedException {
        WorkflowManager tempParent = WorkflowManager.lazyInitTemplateWorkflowRoot();
        SubNodeContainer copy = null;
        ReferencedFile workflowDirRef = new ReferencedFile(directory);
        directory.mkdir();
        workflowDirRef.lock();
        try {
            WorkflowCopyContent cnt = new WorkflowCopyContent();
            cnt.setNodeIDs(getID());
            synchronized (m_nodeMutex) {
                cnt = tempParent.copyFromAndPasteHere(getParent(), cnt);
            }
            NodeID cID = cnt.getNodeIDs()[0];
            copy = ((SubNodeContainer)tempParent.getNodeContainer(cID));
            MetaNodeTemplateInformation template =
                    MetaNodeTemplateInformation.createNewTemplate(SubNodeContainer.class);
            synchronized (copy.m_nodeMutex) {
                copy.setTemplateInformation(template);
                copy.setName(null);
                NodeSettings templateSettings = MetaNodeTemplateInformation.createNodeSettingsForTemplate(copy);
                templateSettings.saveToXML(new FileOutputStream(
                    new File(workflowDirRef.getFile(), WorkflowPersistor.TEMPLATE_FILE)));
                FileSingleNodeContainerPersistor.save(copy, workflowDirRef, exec, new WorkflowSaveHelper(true, false));
            }
            return template;
        } finally {
            if (copy != null) {
                tempParent.removeNode(copy.getID());
            }
            workflowDirRef.unlock();
        }
    }

    /**
     * {@inheritDoc}
     * @since 2.10
     */
    @Override
    public void notifyTemplateConnectionChangedListener() {
        notifyNodePropertyChangedListener(NodeProperty.TemplateConnection);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateMetaNodeLinkInternalRecursively(final ExecutionMonitor exec, final WorkflowLoadHelper loadHelper,
        final Map<URI, NodeContainerTemplate> visitedTemplateMap, final NodeContainerTemplateLinkUpdateResult loadRes)
        throws Exception {
        m_wfm.updateMetaNodeLinkInternalRecursively(exec, loadHelper, visitedTemplateMap, loadRes);
    }

    /** {@inheritDoc} */
    @Override
    public Map<NodeID, NodeContainerTemplate> fillLinkedTemplateNodesList(
        final Map<NodeID, NodeContainerTemplate> mapToFill, final boolean recurse,
        final boolean stopRecursionAtLinkedMetaNodes) {
        return m_wfm.fillLinkedTemplateNodesList(mapToFill, recurse, stopRecursionAtLinkedMetaNodes);
    }

    /** {@inheritDoc} */
    @Override
    public boolean containsExecutedNode() {
        return m_wfm.containsExecutedNode();
    }

    /** {@inheritDoc} */
    @Override
    public Collection<NodeContainer> getNodeContainers() {
        return m_wfm.getNodeContainers();
    }

}
