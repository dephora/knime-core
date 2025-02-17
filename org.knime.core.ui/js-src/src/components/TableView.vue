<!-- eslint-disable max-lines -->
<script>
import Vue from 'vue';
import { JsonDataService, SelectionService } from '@knime/ui-extension-service';
import { TableUI } from '@knime/knime-ui-table';
import { createDefaultFilterConfig, arrayEquals, isImage } from '@/utils/tableViewUtils';
import throttle from 'raf-throttle';
import { MIN_COLUMN_SIZE, SPECIAL_COLUMNS_SIZE, DATA_COLUMNS_MARGIN } from '@knime/knime-ui-table/util/constants';

const INDEX_SYMBOL = Symbol('Index');
const ROW_KEY_SYMBOL = Symbol('Row Key');
// -1 is the backend representation (columnName) for sorting the table by rowKeys
const ROW_KEYS_SORT_COL_NAME = '-1';
const MIN_SCOPE_SIZE = 200;
const MIN_BUFFER_SIZE = 50;

export default {
    components: {
        TableUI
    },
    inject: ['getKnimeService'],
    data() {
        return {
            dataLoaded: false,
            currentIndex: 0,
            currentPage: 1,
            columnSortIndex: null,
            columnSortDirection: null,
            columnSortColumnName: null,
            currentSelection: null,
            totalSelected: 0,
            table: {},
            colNameSelectedRendererId: {},
            dataTypes: {},
            columnDomainValues: {},
            currentSelectedRowKeys: new Set(),
            totalRowCount: 0,
            currentRowCount: 0,
            columnCount: 0,
            settings: {},
            displayedColumns: [],
            jsonDataService: null,
            selectionService: null,
            searchTerm: '',
            columnFilters: [],
            columnIndexMap: null,
            baseUrl: null,
            clientWidth: 0,
            columnSizeOverrides: {},
            numRowsAbove: 0,
            scopeSize: MIN_SCOPE_SIZE,
            bufferSize: MIN_BUFFER_SIZE
        };
    },
    computed: {
        knimeService() {
            return this.getKnimeService();
        },
        specContainsImages() {
            return this.table.columnContentTypes.some(contentType => isImage(contentType));
        },
        dataConfig() {
            const { showRowKeys, showRowIndices, compactMode } = this.settings;
            let columnConfigs = [];
            if (showRowIndices) {
                columnConfigs.push(this.createColumnConfig({ index: 0, columnName: 'Index', isSortable: false }));
            }
            if (showRowKeys) {
                columnConfigs.push(this.createColumnConfig({ index: 1, columnName: 'Row Key', isSortable: true }));
            }
            this.displayedColumns.forEach((columnName, index) => {
                // + 2: offset for the index and rowKey, because the first column
                // (index 0) always contains the indices and the second one the row keys
                const { showColumnDataType, enableRendererSelection } = this.settings;
                const columnInformation = {
                    index: index + 2,
                    columnName,
                    contentType: this.table.columnContentTypes?.[index],
                    ...showColumnDataType && {
                        columnTypeName: this.dataTypes[this.table.columnDataTypeIds?.[index]]?.name
                    },
                    ...enableRendererSelection && {
                        columnTypeRenderers: this.dataTypes[this.table.columnDataTypeIds?.[index]]?.renderers
                    },
                    isSortable: true
                };
                columnConfigs.push(this.createColumnConfig(columnInformation));
            });
            this.updateColumnIndexMap(columnConfigs);
            return {
                columnConfigs,
                rowConfig: { ...this.specContainsImages && { rowHeight: 80 }, compactMode }
            };
        },
        tableConfig() {
            const { enableSortingByHeader, enableGlobalSearch, enableColumnSearch,
                publishSelection, subscribeToSelection, pageSize, enablePagination } = this.settings;
            return {
                subMenuItems: false,
                showSelection: publishSelection || subscribeToSelection,
                showColumnFilters: enableColumnSearch,
                pageConfig: {
                    currentSize: this.currentRowCount,
                    tableSize: this.totalRowCount,
                    pageSize: enablePagination ? pageSize : this.currentRowCount,
                    currentPage: this.currentPage,
                    columnCount: this.columnCount
                },
                enableVirtualScrolling: true,
                fitToContainer: true,
                ...enableSortingByHeader && {
                    sortConfig: {
                        sortColumn: this.columnSortIndex,
                        sortDirection: this.columnSortDirection
                    }
                },
                ...enableGlobalSearch && {
                    searchConfig: {
                        searchQuery: ''
                    }
                }
            };
        },
        numberOfDisplayedIdColumns() {
            let offset = this.settings.showRowKeys ? 1 : 0;
            offset += this.settings.showRowIndices ? 1 : 0;
            return offset;
        },
        numberOfDisplayedColumns() {
            return this.displayedColumns.length + this.numberOfDisplayedIdColumns;
        },
        numberOfUsedColumns() {
            // The columns sent to the TableUI. The rowIndex and rowKey are included but might not be displayed.
            return this.displayedColumns.length + 2;
        },
        columnSizes() {
            const nColumns = this.numberOfDisplayedColumns;
            if (nColumns < 1) {
                return [];
            }

            const specialColumnsSizeTotal = (this.settings.enableColumnSearch ? SPECIAL_COLUMNS_SIZE : 0) +
                (this.settings.publishSelection || this.settings.subscribeToSelection ? SPECIAL_COLUMNS_SIZE : 0);
            const dataColumnsSizeTotal = this.clientWidth - specialColumnsSizeTotal - nColumns * DATA_COLUMNS_MARGIN;
            const defaultColumnSize = Math.max(MIN_COLUMN_SIZE, dataColumnsSizeTotal / nColumns);

            const currentColumnSizes = this.displayedColumns.reduce((columnSizes, columnName) => {
                columnSizes.push(this.columnSizeOverrides[columnName] || defaultColumnSize);
                return columnSizes;
            }, [this.columnSizeOverrides[INDEX_SYMBOL] || defaultColumnSize,
                this.columnSizeOverrides[ROW_KEY_SYMBOL] || defaultColumnSize]);
            const lastColumnMinSize = this.lastColumnMinSize(dataColumnsSizeTotal, currentColumnSizes);
            currentColumnSizes[currentColumnSizes.length - 1] = Math.max(lastColumnMinSize,
                currentColumnSizes[currentColumnSizes.length - 1]);
            return currentColumnSizes;
        },
        rowData() {
            return this.table.rows.map((row, index) => [index, ...row]);
        },
        columnFilterValues() {
            const columnFilterValues = [];
            this.columnFilters.forEach((filter, index) => {
                // filter out empty dummy filter of rowIndices
                if (index === 0) {
                    return;
                }
                if (typeof filter.value === 'string') {
                    columnFilterValues.push([filter.value]);
                } else {
                    columnFilterValues.push(filter.value);
                }
            });
            return columnFilterValues;
        },
        colFilterActive() {
            return this.columnFilterValues.some(val => val.length && val[0] !== '');
        },
        colSortActive() {
            return this.columnSortColumnName !== null && this.columnSortIndex !== null;
        },
        showTitle() {
            return this.settings.showTitle;
        },
        useLazyLoading() {
            return !this.settings.enablePagination;
        },
        selectedRendererIds() {
            return this.getCurrentSelectedRenderers(this.displayedColumns);
        }
    },
    async mounted() {
        const clientWidth = this.$el.getBoundingClientRect().width;
        // clientWidth can be 0, e.g., if table is not visible (yet)
        if (clientWidth) {
            this.clientWidth = clientWidth;
            window.addEventListener('resize', this.onResize);
        } else {
            this.observeTableIntersection();
        }

        this.jsonDataService = new JsonDataService(this.knimeService);
        this.jsonDataService.addOnDataChangeCallback(this.onViewSettingsChange.bind(this));
        const initialData = await this.jsonDataService.initialData();
        this.selectionService = new SelectionService(this.knimeService);
        this.baseUrl = this.knimeService?.extensionConfig?.resourceInfo?.baseUrl;

        if (initialData) {
            const { table, dataTypes, columnDomainValues, columnCount, settings } = initialData;
            this.rowCount = table.rowCount;
            this.displayedColumns = table.displayedColumns;
            this.dataTypes = dataTypes;
            this.columnDomainValues = columnDomainValues;
            this.totalRowCount = this.rowCount;
            this.currentRowCount = this.rowCount;
            this.columnCount = columnCount;
            this.settings = settings;
            if (this.useLazyLoading) {
                await this.initializeLazyLoading();
            } else {
                this.table = table;
            }
            await this.handleInitialSelection();
            const { publishSelection, subscribeToSelection } = settings;
            this.selectionService.onInit(this.onSelectionChange, publishSelection, subscribeToSelection);
            this.dataLoaded = true;
            this.columnFilters = this.getDefaultFilterConfigs(this.displayedColumns);
        }
    },
    beforeDestroy() {
        window.removeEventListener('resize', this.onResize);
    },
    methods: {

        lastColumnMinSize(dataColumnsSizeTotal, currentColumnSizes) {
            return dataColumnsSizeTotal -
                (this.settings.showRowIndices ? currentColumnSizes[0] : 0) -
                (this.settings.showRowKeys ? currentColumnSizes[1] : 0) -
                currentColumnSizes.slice(2, currentColumnSizes.length - 1).reduce((sum, size) => sum + size, 0);
        },

        async initializeLazyLoading(params) {
            const { updateDisplayedColumns = false, updateTotalSelected = true } = params || {};
            this.currentScopeStartIndex = 0;
            await this.updateData({
                lazyLoad: this.getLazyLoadParamsForCurrentScope(),
                updateDisplayedColumns,
                updateTotalSelected
            });
        },

        getLazyLoadParamsForCurrentScope() {
            const numRows = Math.min(this.scopeSize, this.rowCount);
            this.currentScopeEndIndex = numRows;
            return {
                loadFromIndex: this.currentScopeStartIndex,
                newScopeStart: this.currentScopeStartIndex,
                numRows
            };
        },

        onScroll({ direction, startIndex, endIndex }) {
            const prevScopeStart = this.currentScopeStartIndex;
            const prevScopeEnd = this.currentScopeEndIndex;
            const prevScopeSize = this.scopeSize;
            this.scopeSize = this.computeScopeSize(startIndex, endIndex);
            /** we only force an update on a scope size change if it is significant
             * (since startIndex and endIndex do not have a fixed distance even if the
             * height of the scroller is not changed)
             */
            const scopeChangeThreshold = 10;
            const scopeSizeChanged = Math.abs(prevScopeSize - this.scopeSize) > scopeChangeThreshold;
            let bufferStart, bufferEnd, newScopeStart, loadFromIndex, numRows;
            if (direction > 0) {
                if (prevScopeEnd - endIndex > this.bufferSize && !scopeSizeChanged) {
                    return;
                }
                // keep bufferSize elements above the current first visible element or keep all previous rows if the
                // update is due to a change in scope size
                bufferStart = scopeSizeChanged ? prevScopeStart : Math.max(startIndex - this.bufferSize, 0);
                // keep the already loaded elements below the current last visible.
                bufferEnd = Math.max(bufferStart, prevScopeEnd);
                // The next scope consist of numRows newly loaded rows and the buffer.
                // the size of the next scope should be this.scopeSize again (or less at the bottom of the table).
                numRows = Math.min(this.scopeSize - (bufferEnd - bufferStart), this.rowCount - bufferEnd);
                newScopeStart = bufferStart;
                loadFromIndex = bufferEnd;
            } else {
                if (startIndex - prevScopeStart > this.bufferSize && !scopeSizeChanged) {
                    return;
                }
                // keep bufferSize elements below the current last visible or keep all previous rows if the
                // update is due to a change in scope size
                bufferEnd = scopeSizeChanged ? prevScopeEnd : Math.min(endIndex + this.bufferSize, this.rowCount);
                // keep already loaded elements above the current first visible.
                bufferStart = Math.min(bufferEnd, prevScopeStart);
                // The next scope consist of numRows newly loaded rows and the buffer.
                // the size of the next scope should be this.scopeSize again (or less at the top of the table).
                numRows = Math.min(bufferStart, this.scopeSize - (bufferEnd - bufferStart));
                newScopeStart = loadFromIndex = bufferStart - numRows;
            }
            if (numRows > 0) {
                this.currentScopeStartIndex = newScopeStart;
                this.currentScopeEndIndex = newScopeStart + (bufferEnd - bufferStart) + numRows;
                this.updateData({ lazyLoad: {
                    loadFromIndex,
                    numRows,
                    bufferStart,
                    bufferEnd,
                    direction,
                    newScopeStart
                } });
            }
        },

        computeScopeSize(startIndex, endIndex) {
            return Math.max(endIndex - startIndex + 2 * this.bufferSize, MIN_SCOPE_SIZE);
        },

        async updateData(params) {
            const { lazyLoad, updateTotalSelected = false, updateDisplayedColumns = false,
                updateColumnContentTypes = false } = params;
            let loadFromIndex, numRows;
            if (lazyLoad) {
                ({ loadFromIndex, numRows } = lazyLoad);
            } else {
                loadFromIndex = this.currentIndex;
                numRows = this.settings.pageSize;
            }
            const displayedColumns = this.getColumnsForRequest(updateDisplayedColumns);
            const receivedTable = await this.requestTable(loadFromIndex, numRows, displayedColumns,
                updateDisplayedColumns, updateTotalSelected, this.settings.enablePagination);
            if (updateDisplayedColumns) {
                this.columnFilters = this.getDefaultFilterConfigs(receivedTable.displayedColumns);
                this.displayedColumns = receivedTable.displayedColumns;
            }
            if (receivedTable.rowKeys) {
                this.currentRowKeys = receivedTable.rowKeys;
            }
            if (updateColumnContentTypes) {
                this.table.columnContentTypes = receivedTable.columnContentTypes;
            }
            if (lazyLoad) {
                const { newScopeStart, direction, bufferStart, bufferEnd } = lazyLoad;
                const newScope = this.combineWithPrevious(receivedTable.rows, bufferStart, bufferEnd, direction);
                const rows = this.fillWithEmptyRows(newScope, newScopeStart, receivedTable.rowCount);
                if (typeof this.table.rows === 'undefined') {
                    this.table = { ...receivedTable, rows };
                } else {
                    this.table.rows = rows;
                    this.table.rowCount = receivedTable.rowCount;
                }
            } else {
                this.table = receivedTable;
            }
            this.currentRowCount = this.table.rowCount;
            this.transformSelection();
            this.numRowsAbove = lazyLoad ? lazyLoad.newScopeStart : 0;
        },

        // eslint-disable-next-line max-params
        async requestTable(startIndex, numRows, displayedColumns, updateDisplayedColumns, updateTotalSelected,
            clearImageDataCache) {
            const selectedRendererIds = updateDisplayedColumns
                ? this.getCurrentSelectedRenderers(this.settings.displayedColumns)
                : this.selectedRendererIds;
            let receivedTable;
            // if columnSortColumnName is present a sorting is active
            if (this.columnSortColumnName || this.searchTerm || this.colFilterActive) {
                receivedTable = await this.requestFilteredAndSortedTable(startIndex, numRows, displayedColumns,
                    updateDisplayedColumns, updateTotalSelected, selectedRendererIds, clearImageDataCache);
                if (updateTotalSelected) {
                    this.totalSelected = receivedTable.totalSelected;
                }
            } else {
                receivedTable = await this.requestUnfilteredAndUnsortedTable(startIndex, numRows, displayedColumns,
                    updateDisplayedColumns, selectedRendererIds, clearImageDataCache);
                if (updateTotalSelected) {
                    this.totalSelected = this.currentSelectedRowKeys.size;
                }
            }
            return receivedTable;
        },

        // eslint-disable-next-line max-params
        requestFilteredAndSortedTable(startIndex, numRows, displayedColumns, updateDisplayedColumns,
            updateTotalSelected, selectedRendererIds, clearImageDataCache) {
            const columnSortIsAscending = this.columnSortDirection === 1;
            return this.requestNewData('getFilteredAndSortedTable',
                [
                    displayedColumns,
                    Math.min(this.totalRowCount - 1, Math.max(0, startIndex)),
                    numRows,
                    this.columnSortColumnName,
                    columnSortIsAscending,
                    this.searchTerm,
                    updateDisplayedColumns ? null : this.columnFilterValues,
                    this.settings.showRowKeys,
                    selectedRendererIds,
                    updateDisplayedColumns,
                    updateTotalSelected,
                    clearImageDataCache
                ]);
        },

        // eslint-disable-next-line max-params
        requestUnfilteredAndUnsortedTable(startIndex, numRows, displayedColumns, updateDisplayedColumns,
            selectedRendererIds, clearImageDataCache) {
            return this.requestNewData('getTable', [displayedColumns, startIndex, numRows, selectedRendererIds,
                updateDisplayedColumns, clearImageDataCache]);
        },

        getColumnsForRequest(updateDisplayedColumns) {
            return updateDisplayedColumns ? this.settings.displayedColumns : this.displayedColumns;
        },

        requestNewData(method, options) {
            return this.jsonDataService.data({ method, options });
        },

        fillWithEmptyRows(nonEmptyRows, startIndex, rowCount) {
            const result = Array(rowCount).fill([]);
            result.splice(startIndex, nonEmptyRows.length, ...nonEmptyRows);
            return result;
        },

        combineWithPrevious(newRows, bufferStart, bufferEnd, direction) {
            if (bufferStart === bufferEnd) {
                return newRows;
            }
            const buffer = this.table.rows.slice(bufferStart, bufferEnd);
            if (direction > 0) {
                return [...buffer, ...newRows];
            } else {
                return [...newRows, ...buffer];
            }
        },

        refreshTable(params) {
            let { updateDisplayedColumns = false, resetPage = false, updateTotalSelected = false } = params || {};
            const tableUI = this.$refs.tableUI;
            if (tableUI) {
                tableUI.refreshScroller();
            }
            if (resetPage) {
                this.currentPage = 1;
                this.currentIndex = 0;
            }
            if (this.useLazyLoading) {
                this.initializeLazyLoading({ updateDisplayedColumns, updateTotalSelected });
            } else {
                this.updateData({ updateDisplayedColumns, updateTotalSelected });
            }
        },

        onPageChange(pageDirection) {
            const { pageSize } = this.settings;
            this.currentPage += pageDirection;
            this.currentIndex += pageDirection * pageSize;
            this.refreshTable();
        },

        onViewSettingsChange(event) {
            const newSettings = event.data.data.view;
            const enablePaginationChanged = newSettings.enablePagination !== this.settings.enablePagination;
            const displayedColumnsChanged =
                !arrayEquals(newSettings.displayedColumns, this.settings.displayedColumns);
            const showRowKeysChanged = newSettings.showRowKeys !== this.settings.showRowKeys;
            const showRowIndicesChanged = newSettings.showRowIndices !== this.settings.showRowIndices;
            const pageSizeChanged = newSettings.pageSize !== this.settings.pageSize;
            const compactModeChangeInducesRefresh = this.useLazyLoading &&
                (newSettings.compactMode !== this.settings.compactMode);

            this.settings = newSettings;

            const numberOfDisplayedColsChanged = displayedColumnsChanged || showRowKeysChanged || showRowIndicesChanged;
            let sortingParamsReseted = false;
            if (this.colSortActive && numberOfDisplayedColsChanged) {
                sortingParamsReseted = this.updateSortingParams(newSettings, displayedColumnsChanged,
                    showRowKeysChanged, showRowIndicesChanged);
            }
            if (compactModeChangeInducesRefresh || sortingParamsReseted) {
                this.refreshTable();
            } else if (displayedColumnsChanged) {
                this.refreshTable({ updateDisplayedColumns: true, updateTotalSelected: true });
            } else if (pageSizeChanged || enablePaginationChanged) {
                this.refreshTable({ resetPage: true });
            }
            this.selectionService.onSettingsChange(() => Array.from(this.currentSelectedRowKeys), this.clearSelection,
                newSettings.publishSelection, newSettings.subscribeToSelection);
        },
        onColumnSort(newColumnSortIndex) {
            const mappedDisplayedColumns = [null, ROW_KEYS_SORT_COL_NAME, ...this.displayedColumns];
            // if columnSortIndex equals newColumnSortIndex sorting is ascending as default is descending
            const ascendingSort = this.columnSortIndex === newColumnSortIndex && this.columnSortDirection < 0;
            this.columnSortDirection = ascendingSort ? 1 : -1;
            this.currentPage = 1;
            this.currentIndex = 0;
            this.columnSortIndex = newColumnSortIndex;

            this.columnSortColumnName = mappedDisplayedColumns[this.columnIndexMap.get(this.columnSortIndex)];
            this.refreshTable();
        },
        async onSelectionChange(rawEvent) {
            const { selection, mode } = rawEvent;
            if (mode === 'REPLACE') {
                this.currentSelectedRowKeys = new Set(selection);
            } else {
                const addOrDelete = mode === 'ADD' ? 'add' : 'delete';
                this.updateCurrentSelectedRowKeys(addOrDelete, selection);
            }
            this.transformSelection();
            this.totalSelected = await this.requestTotalSelected();
        },
        requestTotalSelected() {
            if (this.searchTerm || this.colFilterActive) {
                return this.requestNewData('getTotalSelected');
            } else {
                return this.currentSelectedRowKeys.size;
            }
        },
        onRowSelect(selected, rowInd) {
            const rowKey = this.table.rows[rowInd][0];
            this.totalSelected += selected ? 1 : -1;
            this.updateSelection(selected, [rowKey]);
        },
        async onSelectAll(selected) {
            const filterActive = this.currentRowCount !== this.totalRowCount;
            if (selected) {
                const currentRowKeys = await this.requestNewData('getCurrentRowKeys', []);
                if (filterActive) {
                    this.updateCurrentSelectedRowKeys('add', currentRowKeys);
                } else {
                    this.currentSelectedRowKeys = new Set(currentRowKeys);
                }
                this.selectionService.publishOnSelectionChange('add', currentRowKeys);
            } else if (filterActive) {
                const currentRowKeys = await this.requestNewData('getCurrentRowKeys', []);
                this.updateCurrentSelectedRowKeys('delete', currentRowKeys);
                this.selectionService.publishOnSelectionChange('remove', currentRowKeys);
            } else {
                this.currentSelectedRowKeys = new Set();
                this.selectionService.publishOnSelectionChange('replace', []);
            }
            this.transformSelection();
            this.totalSelected = selected ? this.currentRowCount : 0;
        },
        onSearch(input) {
            this.searchTerm = input;
            this.refreshTable({ resetPage: true, updateTotalSelected: true });
        },
        onColumnFilter(colInd, value) {
            this.columnFilters[this.columnIndexMap.get(colInd)].value = value;
            this.refreshTable({ resetPage: true, updateTotalSelected: true });
        },
        onClearFilter() {
            this.columnFilters = this.getDefaultFilterConfigs(this.displayedColumns);
            this.refreshTable({ resetPage: true, updateTotalSelected: true });
        },
        onColumnResize(columnIndex, newColumnSize) {
            const colName = this.dataConfig.columnConfigs[columnIndex].header;
            if (columnIndex < this.numberOfDisplayedIdColumns) {
                if (colName === 'Index') {
                    Vue.set(this.columnSizeOverrides, INDEX_SYMBOL, newColumnSize);
                } else {
                    Vue.set(this.columnSizeOverrides, ROW_KEY_SYMBOL, newColumnSize);
                }
            } else {
                Vue.set(this.columnSizeOverrides, colName, newColumnSize);
            }
        },
        observeTableIntersection() {
            new IntersectionObserver((entries, observer) => {
                entries.forEach((entry) => {
                    if (entry.target === this.$el && entry.boundingClientRect.width) {
                        this.clientWidth = entry.boundingClientRect.width;
                        // observer is either removed here or on garbage collection
                        observer.unobserve(entry.target);
                        window.addEventListener('resize', this.onResize);
                    }
                });
            }).observe(this.$el);
        },
        onResize: throttle(function () {
            /* eslint-disable no-invalid-this */
            const updatedClientWidth = this.$el.getBoundingClientRect().width;
            if (updatedClientWidth) {
                // also update all overridden column widths according to the relative change in client width
                const ratio = updatedClientWidth / this.clientWidth;
                Object.keys(this.columnSizeOverrides).forEach(key => {
                    this.columnSizeOverrides[key] *= ratio;
                });
                Object.getOwnPropertySymbols(this.columnSizeOverrides).forEach(symbol => {
                    this.columnSizeOverrides[symbol] *= ratio;
                });
                this.clientWidth = updatedClientWidth;
            } else {
                this.observeTableIntersection();
                window.removeEventListener('resize', this.onResize);
            }
            /* eslint-enable no-invalid-this */
        }),
        onHeaderSubMenuItemSelection(item, colInd) {
            if (item.section === 'dataRendering') {
                this.$set(this.colNameSelectedRendererId,
                    this.displayedColumns[colInd - this.numberOfDisplayedIdColumns], item.id);
            }
            this.updateData({
                ...this.useLazyLoading && { lazyLoad: this.getLazyLoadParamsForCurrentScope() },
                updateColumnContentTypes: true
            });
        },
        updateSelection(selected, rowKeys) {
            this.selectionService.publishOnSelectionChange(selected ? 'add' : 'remove', rowKeys);
            this.updateCurrentSelectedRowKeys(selected ? 'add' : 'delete', rowKeys);
            this.transformSelection();
        },
        updateCurrentSelectedRowKeys(addOrDelete, selectedRowKeys) {
            selectedRowKeys.forEach(selectedRowKey => this.currentSelectedRowKeys[addOrDelete](selectedRowKey));
        },
        transformSelection() {
            if (typeof this.table.rows === 'undefined') {
                return;
            }
            const rowKeys = this.table.rows.map(row => row[0]).filter(x => typeof x !== 'undefined');
            this.currentSelection = rowKeys.map(rowKey => this.currentSelectedRowKeys.has(rowKey));
        },
        clearSelection() {
            this.currentSelectedRowKeys = new Set();
            this.totalSelected = 0;
            this.transformSelection();
        },
        async handleInitialSelection() {
            if (this.settings.subscribeToSelection) {
                const initialSelection = await this.selectionService.initialSelection();
                this.currentSelectedRowKeys = new Set(initialSelection);
                this.totalSelected = initialSelection.length;
                this.transformSelection();
            } else {
                this.clearSelection();
            }
        },
        createHeaderSubMenuItems(columnName, renderers) {
            const headerSubMenuItems = [];
            headerSubMenuItems.push({ text: 'Data renderer', separator: true, sectionHeadline: true });
            renderers.forEach(renderer => {
                headerSubMenuItems.push({
                    text: renderer.name,
                    title: renderer.name,
                    id: renderer.id,
                    section: 'dataRendering',
                    selected: this.colNameSelectedRendererId[columnName] === renderer.id
                });
            });
            return headerSubMenuItems;
        },
        createColumnConfig(columnInformation) {
            const { index, columnName, columnTypeName, contentType, isSortable, columnTypeRenderers } =
                columnInformation;
            return {
                key: index,
                header: columnName,
                subHeader: columnTypeName,
                hasSlotContent: isImage(contentType),
                size: this.columnSizes[index],
                filterConfig: this.columnFilters[index],
                ...columnTypeRenderers && {
                    headerSubMenuItems: this.createHeaderSubMenuItems(columnName, columnTypeRenderers)
                },
                formatter: val => val,
                isSortable
            };
        },
        getDefaultFilterConfigs(displayedColumns) {
            // default filter config for rowKey and empty config for rowIndex
            const filterConfigs = [
                {
                    is: '',
                    value: ''
                },
                createDefaultFilterConfig(false, null)
            ];
            displayedColumns.forEach(col => {
                const domainValues = this.columnDomainValues[col];
                const domainValuesMapped = domainValues ? domainValues.map(val => ({ id: val, text: val })) : null;
                filterConfigs.push(createDefaultFilterConfig(domainValues, domainValuesMapped));
            });
            return filterConfigs;
        },
        // only call the method when (displayedColumns XOR showRowKeys XOR showRowKeys) changed and a sorting is active
        updateSortingParams(newSettings, displayedColumnsChanged, showRowKeysChanged, showRowIndicesChanged) {
            const { showRowKeys, showRowIndices, displayedColumns } = newSettings;
            if (displayedColumnsChanged) {
                // sort on column (not rowKey column) and add/remove (different) column (not rowKey/rowIndex column)
                if (this.columnSortColumnName !== ROW_KEYS_SORT_COL_NAME) {
                    const newColIndexOfSortCol = displayedColumns.indexOf(this.columnSortColumnName);
                    if (newColIndexOfSortCol === -1) {
                        this.resetSorting();
                        return true;
                    } else {
                        this.columnSortIndex = newColIndexOfSortCol + this.numberOfDisplayedIdColumns;
                    }
                }
                // no change when sorting the rowKey column and adding/removing columns behind the rowKey column
                // current sorting is on rowKey column which is removed
            } else if (this.columnSortColumnName === ROW_KEYS_SORT_COL_NAME && !showRowKeys) {
                this.resetSorting();
                return true;
                // rowKey or rowIndex column is added
            } else if ((showRowKeys && showRowKeysChanged) || (showRowIndices && showRowIndicesChanged)) {
                this.columnSortIndex += 1;
                // rowKey or rowIndex column is removed
            } else {
                this.columnSortIndex -= 1;
            }
            return false;
        },
        updateColumnIndexMap(columnConfigs) {
            this.columnIndexMap = new Map(columnConfigs.map((config, index) => [index, config.key]));
        },
        getImageUrl(data, index) {
            // the columnConfigs contain at index 0 rowIndices, at index 1 rowKeys, and at index 2+ the data
            // the rowData consists of [rowIndices?, rowkeys, ...data] (rowIndices if showRowIndices)
            // we need to map from the columnConfig data indices to the rowData data indices
            return this.$store.getters['api/uiExtResourceLocation']({
                resourceInfo: {
                    baseUrl: this.baseUrl,
                    path: data.data.row[index - (this.numberOfUsedColumns - this.numberOfDisplayedColumns)]
                }
            });
        },
        resetSorting() {
            this.columnSortColumnName = null;
            this.columnSortIndex = null;
            this.columnSortDirection = null;
            this.currentPage = 1;
            this.currentIndex = 0;
        },
        getCurrentSelectedRenderers(displayedColumns) {
            return displayedColumns.map(
                columnName => this.colNameSelectedRendererId[columnName] || null
            );
        }
    }
};
</script>

<template>
  <div class="table-view-wrapper">
    <h4
      v-if="showTitle"
      class="table-title"
    >
      {{ settings.title }}
    </h4>
    <TableUI
      v-if="dataLoaded && numberOfDisplayedColumns > 0"
      ref="tableUI"
      :data="[rowData]"
      :current-selection="[currentSelection]"
      :total-selected="totalSelected"
      :data-config="dataConfig"
      :table-config="tableConfig"
      :num-rows-above="numRowsAbove"
      @pageChange="onPageChange"
      @columnSort="onColumnSort"
      @rowSelect="onRowSelect"
      @selectAll="onSelectAll"
      @search="onSearch"
      @columnFilter="onColumnFilter"
      @clearFilter="onClearFilter"
      @columnResize="onColumnResize"
      @headerSubMenuItemSelection="onHeaderSubMenuItemSelection"
      @lazyload="onScroll"
    >
      <template
        v-for="index in numberOfUsedColumns"
        #[`cellContent-${index}`]="data"
      >
        <img
          :key="index"
          loading="lazy"
          :src="getImageUrl(data, index)"
          alt=""
        >
      </template>
    </TableUI>
    <div
      v-else-if="dataLoaded"
      class="no-columns"
    >
      <h4>
        No data to display
      </h4>
    </div>
  </div>
</template>

<style lang="postcss" scoped>
.table-view-wrapper {
  height: 100%;
  display: flex;
  flex-direction: column;

  & >>> .table-header {
    background-color: var(--knime-porcelain);
  }

  & >>> .row {
    border-bottom: 1px solid var(--knime-porcelain);
    align-content: center;

    & img {
      object-fit: contain;
      max-width: 100%;
      max-height: 100%;
      vertical-align: middle;
    }
  }
}

.table-title {
  margin: 0;
  padding: 15px 0, 5px 5px;
  color: rgb(70 70 70);
  font-size: 20px;
}

.no-columns {
  height: 100%;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;

  & h4 {
    color: rgb(70 70 70);
    font-size: 16px;
  }
}
</style>
