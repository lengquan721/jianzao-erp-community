<script setup lang="ts">
/**
 * 商品资料表单字段（商品资料弹窗 / 销售「现录现卖」共用）
 *
 * 两处都是「新建一个商品」，字段口径必须一致，否则现录现卖建出来的商品信息不全。
 * 组件只负责渲染字段，校验规则由父级表单的 rules 决定（字段名与本组件的 name 一一对应）。
 *
 * 用法：
 *   <ProductFormFields v-model="form" />
 *   外层父级 t-form 提供 :data/:rules 与标签配置。
 *
 * 标签一律由父表单继承（两个父容器都设 label-align="top"），本组件不写死 label-width：
 * 之前写死 105px + 右对齐标签，在 520~640px 的弹窗里把输入框挤到 120px 左右，
 * 现在标签在上、字段两列铺满，抽屉与弹窗都不挤。
 */
import { ERP_PRODUCT_TYPE, ERP_QTY_SCALE_OPTIONS, ERP_TAX_TYPE } from '@/api/erp/enums';
import type { ErpProductType, ErpTaxType } from '@/api/erp/enums';
import { productCategoryOptions, productCategoryOptionsByIds, supplierOptions, supplierOptionsByIds } from '@/api/erp/master';
import type { ErpId, ErpProductFormModel } from '@/api/erp/types';
import ErpPagedSelect from './ErpPagedSelect.vue';

withDefaults(
  defineProps<{
    /** 商品资料只允许新选启用分类，已选停用分类仍按 ID 回显。 */
    activeCategoryOnly?: boolean;
    /** 是否展示「类型」（现录现卖固定成品，不展示） */
    showType?: boolean;
    /** 是否展示「状态」（现录现卖新建即启用，不展示） */
    showStatus?: boolean;
    /** 成本单价只读（商品资料由入库回写；现录现卖可填，作为建品后的最近入库成本） */
    costReadonly?: boolean;
    /** 只读时成本单价下方的说明文案 */
    costHint?: string;
    /** 是否展示「新增分类」入口 */
    showAddCategory?: boolean;
  }>(),
  {
    activeCategoryOnly: false,
    showType: true,
    showStatus: true,
    costReadonly: true,
    costHint: '四种入库确认后系统回写，不可手工修改',
    showAddCategory: false
  }
);

const emit = defineEmits<{
  (e: 'add-category'): void;
}>();

const model = defineModel<ErpProductFormModel>({ required: true });

/** 类型下拉：ERP_PRODUCT_TYPE 是 Record，t-select 只接受 {label,value} 数组 */
const productTypeOptions = Object.entries(ERP_PRODUCT_TYPE).map(([value, item]) => ({ label: item.label, value: value as ErpProductType }));

/** 发票类型下拉：同上，Record 转数组 */
const taxTypeOptions = Object.entries(ERP_TAX_TYPE).map(([value, item]) => ({ label: item.label, value: value as ErpTaxType }));

const nameLabel = (item: { id: ErpId; name?: string }) => item.name ?? String(item.id);
</script>

<template>
  <t-row :gutter="16">
    <t-col :span="12">
      <div class="erp-form-section">基础信息</div>
    </t-col>
    <!-- 产品名称：独占整行（宽展示，便于长品名输入与查看） -->
    <t-col :span="12">
      <t-form-item label="产品名称" name="name">
        <t-input v-model="model.name" placeholder="请输入产品名称" />
      </t-form-item>
    </t-col>
    <!-- 产品编码与类型：有类型时各半行，无类型（现录现卖）时编码整行 -->
    <t-col :span="showType ? 6 : 12">
      <t-form-item label="产品编码" name="code">
        <t-input v-model="model.code" placeholder="留空自动生成（按名称）" />
      </t-form-item>
    </t-col>
    <t-col v-if="showType" :span="6">
      <t-form-item label="类型" name="type">
        <t-select v-model="model.type" placeholder="请选择类型" :options="productTypeOptions" />
      </t-form-item>
    </t-col>
    <!-- 规格与单位：强关联属性成对并排 -->
    <t-col :span="6">
      <t-form-item label="规格" name="spec">
        <t-input v-model="model.spec" placeholder="请输入规格" />
      </t-form-item>
    </t-col>
    <t-col :span="6">
      <t-form-item label="单位" name="unit">
        <t-input v-model="model.unit" placeholder="请输入单位" />
      </t-form-item>
    </t-col>
    <!-- 商品分类与数量精度：计算与分类口径成对并排 -->
    <t-col :span="6">
      <t-form-item label="商品分类" name="categoryId">
        <div class="flex w-full items-center gap-1">
          <ErpPagedSelect
            v-model="model.categoryId"
            placeholder="请选择分类（可留空）"
            :fetch-page="productCategoryOptions"
            :fetch-by-ids="productCategoryOptionsByIds"
            :status="activeCategoryOnly ? 1 : undefined"
            :label="nameLabel"
          />
          <t-button v-if="showAddCategory" v-hasPermi="['erp:product-category:add']" theme="primary" variant="text" @click="emit('add-category')">
            新增
          </t-button>
        </div>
      </t-form-item>
    </t-col>
    <t-col :span="6">
      <t-form-item label="数量精度" name="qtyScale">
        <div class="w-full">
          <t-select v-model="model.qtyScale" placeholder="请选择数量精度" :options="ERP_QTY_SCALE_OPTIONS" />
          <div class="erp-text-secondary">开单时数量输入精度，默认整数</div>
        </div>
      </t-form-item>
    </t-col>
    <!-- 所属供应商：独占整行（容纳长供应商名称，避免下拉截断） -->
    <t-col :span="12">
      <t-form-item label="所属供应商" name="supplierId">
        <ErpPagedSelect
          v-model="model.supplierId"
          placeholder="请选择供应商（可留空）"
          :fetch-page="supplierOptions"
          :fetch-by-ids="supplierOptionsByIds"
          :label="nameLabel"
        />
      </t-form-item>
    </t-col>
    <t-col :span="12">
      <div class="erp-form-section">价格与库存</div>
    </t-col>
    <t-col :span="6">
      <t-form-item label="发票类型" name="taxType">
        <t-select v-model="model.taxType" placeholder="请选择发票类型" :options="taxTypeOptions" />
      </t-form-item>
    </t-col>
    <t-col :span="6">
      <t-form-item label="最近入库成本" name="costPrice">
        <div class="w-full">
          <t-input-number v-model="model.costPrice" :decimal-places="2" :disabled="costReadonly" theme="normal" align="right" style="width: 100%" />
          <div v-if="costReadonly" class="erp-text-secondary">{{ costHint }}</div>
        </div>
      </t-form-item>
    </t-col>
    <!-- 采购价与默认运杂费：采购成本成对并排 -->
    <t-col :span="6">
      <t-form-item label="采购价" name="purchasePrice">
        <t-input-number v-model="model.purchasePrice" :decimal-places="2" theme="normal" align="right" style="width: 100%" />
      </t-form-item>
    </t-col>
    <t-col :span="6">
      <t-form-item label="默认运杂费" name="freight">
        <t-input-number v-model="model.freight" :decimal-places="2" theme="normal" align="right" style="width: 100%" />
      </t-form-item>
    </t-col>
    <!-- 库存下限与库存上限：上下限阈值成对并排 -->
    <t-col :span="6">
      <t-form-item label="库存下限" name="minStock">
        <t-input-number
          v-model="model.minStock"
          :decimal-places="3"
          :min="0"
          theme="normal"
          align="right"
          placeholder="留空不启用"
          style="width: 100%"
        />
      </t-form-item>
    </t-col>
    <t-col :span="6">
      <t-form-item label="库存上限" name="maxStock">
        <t-input-number
          v-model="model.maxStock"
          :decimal-places="3"
          :min="0"
          theme="normal"
          align="right"
          placeholder="留空不启用"
          style="width: 100%"
        />
      </t-form-item>
    </t-col>
    <t-col :span="12">
      <div class="erp-form-section">其他</div>
    </t-col>
    <t-col v-if="showStatus" :span="12">
      <t-form-item label="状态" name="status">
        <t-radio-group v-model="model.status">
          <t-radio :value="1">启用</t-radio>
          <t-radio :value="0">停用</t-radio>
        </t-radio-group>
      </t-form-item>
    </t-col>
    <t-col :span="12">
      <t-form-item label="备注" name="remark">
        <t-textarea v-model="model.remark" :autosize="{ minRows: 2, maxRows: 2 }" placeholder="请输入内容" />
      </t-form-item>
    </t-col>
  </t-row>
</template>

<style scoped lang="scss">
/* 分组小标题：抽屉里字段多，靠它把「基础信息 / 价格与库存 / 其他」分开 */
.erp-form-section {
  margin: 2px 0 10px;
  padding-left: 8px;
  border-left: 3px solid var(--td-brand-color);
  font-size: 13px;
  font-weight: 600;
  line-height: 18px;
  color: var(--td-text-color-primary);
}
</style>
