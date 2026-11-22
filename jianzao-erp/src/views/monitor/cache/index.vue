<template>
  <div class="p-2">
    <t-row :gutter="10">
      <t-col :span="12" class="card-box">
        <t-card :hover-shadow="true">
          <template #header>
            <DesktopIcon style="width: 1em; height: 1em; vertical-align: middle" />
            <span style="vertical-align: middle">基本信息</span>
          </template>

          <div class="cache-info-table">
            <table style="width: 100%">
              <tbody>
                <tr>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">Redis版本</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.redis_version }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">运行模式</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.redis_mode === 'standalone' ? '单机' : '集群' }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">端口</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.tcp_port }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">客户端数</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.connected_clients }}</div>
                  </td>
                </tr>
                <tr>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">运行时间(天)</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.uptime_in_days }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">使用内存</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.used_memory_human }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">使用CPU</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ parseFloat(cache.info.used_cpu_user_children).toFixed(2) }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">内存配置</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.maxmemory_human }}</div>
                  </td>
                </tr>
                <tr>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">AOF是否开启</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.aof_enabled === '0' ? '否' : '是' }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">RDB是否成功</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">{{ cache.info.rdb_last_bgsave_status }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">Key数量</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.dbSize" class="cache-info-text">{{ cache.dbSize }}</div>
                  </td>
                  <td class="cache-info-cell">
                    <div class="cache-info-text">网络入口/出口</div>
                  </td>
                  <td class="cache-info-cell">
                    <div v-if="cache.info" class="cache-info-text">
                      {{ cache.info.instantaneous_input_kbps }}kps/{{ cache.info.instantaneous_output_kbps }}kps
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </t-card>
      </t-col>

      <t-col :span="6" class="card-box">
        <t-card :hover-shadow="true">
          <template #header>
            <ChartPieIcon style="width: 1em; height: 1em; vertical-align: middle" />
            <span style="vertical-align: middle">命令统计</span>
          </template>
          <div class="cache-info-table">
            <div ref="commandstats" style="height: 420px" />
          </div>
        </t-card>
      </t-col>

      <t-col :span="6" class="card-box">
        <t-card :hover-shadow="true">
          <template #header>
            <DashboardIcon style="width: 1em; height: 1em; vertical-align: middle" /> <span style="vertical-align: middle">内存信息</span>
          </template>
          <div class="cache-info-table">
            <div ref="usedmemory" style="height: 420px" />
          </div>
        </t-card>
      </t-col>
    </t-row>
  </div>
</template>

<script setup name="Cache" lang="ts">
import { getCache } from '@/api/monitor/cache';
import * as echarts from 'echarts';
import { CacheVO } from '@/api/monitor/cache/types';
// TDesign 图标必须显式 import，不会自动解析（DOC/35 §3）
import { ChartPieIcon, DashboardIcon, DesktopIcon } from 'tdesign-icons-vue-next';

const cache = ref<Partial<CacheVO>>({});
const commandstats = ref();
const usedmemory = ref();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

let commandstatsInstance: echarts.ECharts | undefined;
let usedmemoryInstance: echarts.ECharts | undefined;
let unmounted = false;

/** 注册与移除必须使用同一个回调引用。 */
const resizeCharts = () => {
  commandstatsInstance?.resize();
  usedmemoryInstance?.resize();
};

const getList = async () => {
  proxy?.$modal.loading('正在加载缓存监控数据，请稍候！');
  try {
    const res = await getCache();
    if (unmounted) return;
    cache.value = res.data;
    commandstatsInstance ??= echarts.init(commandstats.value, 'macarons');
    commandstatsInstance.setOption({
      tooltip: {
        trigger: 'item',
        formatter: '{a} <br/>{b} : {c} ({d}%)'
      },
      series: [
        {
          name: '命令',
          type: 'pie',
          roseType: 'radius',
          radius: [15, 95],
          center: ['50%', '38%'],
          data: res.data.commandStats,
          animationEasing: 'cubicInOut',
          animationDuration: 1000
        }
      ]
    });
    usedmemoryInstance ??= echarts.init(usedmemory.value, 'macarons');
    usedmemoryInstance.setOption({
      tooltip: {
        formatter: '{b} <br/>{a} : ' + cache.value.info.used_memory_human
      },
      series: [
        {
          name: '峰值',
          type: 'gauge',
          min: 0,
          max: 1000,
          detail: {
            formatter: cache.value.info.used_memory_human
          },
          data: [
            {
              value: parseFloat(cache.value.info.used_memory_human),
              name: '内存消耗'
            }
          ]
        }
      ]
    });
  } finally {
    proxy?.$modal.closeLoading();
  }
};

onMounted(() => {
  window.addEventListener('resize', resizeCharts);
  return getList();
});

onBeforeUnmount(() => {
  unmounted = true;
  window.removeEventListener('resize', resizeCharts);
  commandstatsInstance?.dispose();
  usedmemoryInstance?.dispose();
  commandstatsInstance = undefined;
  usedmemoryInstance = undefined;
});
</script>

<style lang="scss" scoped>
// 基本信息表：沿用原表格结构，样式从 EP 表格 class 收敛到本页 scoped 类
.cache-info-table {
  width: 100%;
  font-size: 14px;
  color: var(--td-text-color-primary);
  border-collapse: collapse;

  table,
  td {
    border: 1px solid var(--td-component-border);
  }

  .cache-info-cell {
    padding: 12px 10px;
  }

  .cache-info-text {
    line-height: 23px;
  }
}
</style>
