/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
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
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
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
 *   8 Jun 2022 (carlwitt): created
 */
package org.knime.core.node.workflow;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.Test;
import org.knime.core.node.workflow.ComponentMetadata.ComponentMetadataBuilder;
import org.knime.core.node.workflow.ComponentMetadata.ComponentNodeType;
import org.knime.core.node.workflow.def.DefToCoreUtil;
import org.knime.shared.workflow.def.ComponentMetadataDef;

/**
 *
 * @author Carl Witt, KNIME AG, Zurich, Switzerland
 */
public class DefToCoreUtilTest {

    /**
     * Convert Component Metadata from core to def format and back to check if any data gets lost.
     */
    @Test
    public void testComponentMetadataConversion() {

        final var core = new ComponentMetadataBuilder().addInPortNameAndDescription("inName1", "inDescription1")//
            .addInPortNameAndDescription("inName2", "inDescription2")//
            .addOutPortNameAndDescription("outName1", "outDescription1")//
            .addOutPortNameAndDescription("outName2", "outDescription2")//
            .description("description")//
            .icon(new byte[]{1, 2, 3})//
            .type(ComponentNodeType.SINK)//
            .build();

        final var def = CoreToDefUtil.toComponentMetadataDef(core);

        final var core2 = DefToCoreUtil.toComponentMetadata(def);

        assertThat(core2.getInPortNames().get()[0], is("inName1"));
        assertThat(core2.getInPortNames().get()[1], is("inName2"));
        assertThat(core2.getInPortDescriptions().get()[0], is("inDescription1"));
        assertThat(core2.getInPortDescriptions().get()[1], is("inDescription2"));

        assertThat(core2.getOutPortNames().get()[0], is("outName1"));
        assertThat(core2.getOutPortNames().get()[1], is("outName2"));
        assertThat(core2.getOutPortDescriptions().get()[0], is("outDescription1"));
        assertThat(core2.getOutPortDescriptions().get()[1], is("outDescription2"));

        assertThat(core2.getDescription().get(), is("description"));

        assertThat(core2.getIcon().get(), is(new byte[]{1, 2, 3}));

        assertThat(core2.getNodeType().get(), is(ComponentNodeType.SINK));

    }

    /**
     * Convert Component Metadata from core to def format and back to check if any data gets lost.
     */
    @Test
    public void testComponentTypeNull() {

        final var core = new ComponentMetadataBuilder()//
            .description("description")//
            .build();

        final var def = CoreToDefUtil.toComponentMetadataDef(core);

        final var core2 = DefToCoreUtil.toComponentMetadata(def);

        assertThat(core2.getDescription().get(), is("description"));

        assertThat("", core2.getNodeType().isEmpty());

    }

    /**
     * Convert all enum values back and forth to see if any problems occur.
     */
    @Test
    public void testComponentTypeConversion() {

        for (ComponentNodeType coreType : ComponentNodeType.values()) {
            var def = CoreToDefUtil.toComponentNodeType(coreType);
            var core2 = DefToCoreUtil.toComponentNodeType(def);
            assertThat("Enum conversion between ComponentNodeType and ComponentMetadata.ComponentTypeEnum failed. ",
                core2 == coreType);
        }

        for (ComponentMetadataDef.ComponentTypeEnum defType : ComponentMetadataDef.ComponentTypeEnum.values()) {
            var core = DefToCoreUtil.toComponentNodeType(defType);
            var def2 = CoreToDefUtil.toComponentNodeType(core);
            assertThat("Enum conversion between ComponentNodeType and ComponentMetadataDef.ComponentTypeEnum failed. ",
                def2 == defType);
        }

    }
}
