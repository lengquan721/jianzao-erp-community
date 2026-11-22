// 全局组件类型别名：前端已整体迁至 TDesign，此处保留历史别名名不变，供各页面零改动引用。
// 有对应 TDesign 类型的直接复用其导出；无稳定实例类型或原为组件 InstanceType 的，用宽松类型兜底。
import type * as td from 'tdesign-vue-next';

declare global {
  declare type ElTagType = '' | 'default' | 'primary' | 'success' | 'info' | 'warning' | 'danger';
  declare type ElFormInstance = td.FormInstanceFunctions;
  declare type ElTableInstance = td.TableInstanceFunctions;
  declare type ElUploadInstance = td.UploadInstanceFunctions;
  declare type ElScrollbarInstance = any;
  declare type ElInputInstance = any;
  declare type ElInputNumberInstance = any;
  declare type ElRadioInstance = any;
  declare type ElRadioGroupInstance = any;
  declare type ElRadioButtonInstance = any;
  declare type ElCheckboxInstance = any;
  declare type ElSwitchInstance = any;
  declare type ElCascaderInstance = any;
  declare type ElColorPickerInstance = any;
  declare type ElRateInstance = any;
  declare type ElSliderInstance = any;

  declare type ElTreeInstance = any;
  declare type ElTreeSelectInstance = any;
  declare type ElSelectInstance = any;
  declare type ElCardInstance = any;
  declare type ElDialogInstance = any;
  declare type ElCheckboxGroupInstance = any;
  declare type ElDatePickerInstance = any;
  declare type ElTimePickerInstance = any;
  declare type ElTimeSelectInstance = any;

  declare type TransferKey = string | number;
  declare type CheckboxValueType = string | number | boolean;
  declare type ElFormRules = td.FormRules;
  declare type DateModelType = number | string | Date;
  declare type UploadFile = td.UploadFile;
}
