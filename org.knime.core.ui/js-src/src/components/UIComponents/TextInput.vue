<script>
import { defineComponent } from '@vue/composition-api';
import { rendererProps, useJsonFormsControl } from '@jsonforms/vue2';
import { checkIsModelSetting, getFlowVariablesMap } from '@/utils/nodeDialogUtils';
import InputField from '~/webapps-common/ui/components/forms/InputField.vue';
import LabeledInput from './LabeledInput.vue';

const TextInput = defineComponent({
    name: 'TextInput',
    components: {
        InputField,
        LabeledInput
    },
    props: {
        ...rendererProps()
    },
    setup(props) {
        return useJsonFormsControl(props);
    },
    computed: {
        isModelSetting() {
            return checkIsModelSetting(this.control);
        },
        flowSettings() {
            return getFlowVariablesMap(this.control);
        },
        disabled() {
            return !this.control.enabled || this.flowSettings?.controllingFlowVariableAvailable;
        }
    },

    methods: {
        onChange(event) {
            this.handleChange(this.control.path, event);
            if (this.isModelSetting) {
                this.$store.dispatch('pagebuilder/dialog/dirtySettings', true);
            }
        }
    }
        
});
export default TextInput;
</script>

<template>
  <LabeledInput
    v-if="control.visible"
    :text="control.label"
    :description="control.description"
    :errors="[control.errors]"
    :is-model-setting="isModelSetting"
    :scope="control.uischema.scope"
    :flow-settings="flowSettings"
  >
    <InputField
      :value="control.data"
      :disabled="disabled"
      @input="onChange"
    />
  </LabeledInput>
</template>
