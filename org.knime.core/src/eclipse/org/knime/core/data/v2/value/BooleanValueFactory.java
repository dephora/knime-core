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
 *   Oct 7, 2020 (dietzc): created
 */
package org.knime.core.data.v2.value;

import org.knime.core.data.BooleanValue;
import org.knime.core.data.BoundedValue;
import org.knime.core.data.ComplexNumberValue;
import org.knime.core.data.DataCell;
import org.knime.core.data.DoubleValue;
import org.knime.core.data.FuzzyIntervalValue;
import org.knime.core.data.FuzzyNumberValue;
import org.knime.core.data.IntValue;
import org.knime.core.data.LongValue;
import org.knime.core.data.NominalValue;
import org.knime.core.data.def.BooleanCell;
import org.knime.core.data.v2.ReadValue;
import org.knime.core.data.v2.ValueFactory;
import org.knime.core.data.v2.WriteValue;
import org.knime.core.data.v2.access.BooleanAccess.BooleanAccessSpec;
import org.knime.core.data.v2.access.BooleanAccess.BooleanReadAccess;
import org.knime.core.data.v2.access.BooleanAccess.BooleanWriteAccess;

/**
 * {@link ValueFactory} implementation for {@link BooleanCell}.
 *
 * @author Christian Dietz, KNIME GmbH, Konstanz, Germany
 * @since 4.3
 *
 * @noreference This class is not intended to be referenced by clients.
 */
public class BooleanValueFactory implements ValueFactory<BooleanReadAccess, BooleanWriteAccess> {

    /** Stateless instance of BooleanValueFactory */
    public static final BooleanValueFactory INSTANCE = new BooleanValueFactory();

    @Override
    public BooleanAccessSpec getSpec() {
        return BooleanAccessSpec.INSTANCE;
    }

    @Override
    public BooleanReadValue createReadValue(final BooleanReadAccess reader) {
        return new DefaultBooleanReadValue(reader);
    }

    @Override
    public BooleanWriteValue createWriteValue(final BooleanWriteAccess writer) {
        return new DefaultBooleanWriteValue(writer);
    }

    /**
     * {@link ReadValue} equivalent to {@link BooleanCell}.
     *
     * @author Christian Dietz, KNIME GmbH, Konstanz, Germany
     * @since 4.3
     */
    public static interface BooleanReadValue extends //
        BooleanValue, //
        DoubleValue, //
        BoundedValue, //
        LongValue, //
        IntValue, //
        NominalValue, //
        ComplexNumberValue, //
        FuzzyNumberValue, //
        FuzzyIntervalValue, //
        ReadValue {
    }

    /**
     * {@link WriteValue} equivalent to {@link BooleanCell}.
     *
     * @author Christian Dietz, KNIME GmbH, Konstanz, Germany
     * @since 4.3
     */
    public static interface BooleanWriteValue extends WriteValue<BooleanValue> {

        /**
         * @param value the boolean to set.
         */
        void setBooleanValue(boolean value);
    }

    private static final class DefaultBooleanReadValue extends AbstractValue<BooleanReadAccess>
        implements BooleanReadValue {

        DefaultBooleanReadValue(final BooleanReadAccess access) {
            super(access);
        }

        @Override
        public double getDoubleValue() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public long getLongValue() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public int getIntValue() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public double getRealValue() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public double getImaginaryValue() {
            return 0;
        }

        @Override
        public double getMinSupport() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public double getCore() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public double getMaxSupport() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public double getMinCore() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public double getMaxCore() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public double getCenterOfGravity() {
            return m_access.getBooleanValue() ? 1 : 0;
        }

        @Override
        public DataCell getDataCell() {
            return m_access.getBooleanValue() ? BooleanCell.TRUE : BooleanCell.FALSE;
        }

        @Override
        public boolean getBooleanValue() {
            return m_access.getBooleanValue();
        }

        @Override
        public boolean isMissing() {
            return m_access.isMissing();
        }

    }

    private static final class DefaultBooleanWriteValue extends AbstractValue<BooleanWriteAccess>
        implements BooleanWriteValue {

        DefaultBooleanWriteValue(final BooleanWriteAccess access) {
            super(access);
        }

        @Override
        public void setValue(final BooleanValue value) {
            m_access.setBooleanValue(value.getBooleanValue());
        }

        @Override
        public void setMissing() {
            m_access.setMissing();
        }

        @Override
        public void setBooleanValue(final boolean value) {
            m_access.setBooleanValue(value);

        }

    }

}
