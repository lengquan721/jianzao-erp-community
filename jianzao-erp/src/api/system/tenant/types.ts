export interface TenantVO extends BaseEntity {
  id: number | string;
  tenantId: number | string;
  username: string;
  contactUserName: string;
  contactPhone: string;
  companyName: string;
  licenseNumber: string;
  address: string;
  domain: string;
  intro: string;
  remark: string;
  packageId: string | number;
  /** 省略=不变，null=明确清空，字符串=新日期。 */
  expireTime?: string | null;
  accountCount: number;
  status: string;
}

export interface TenantQuery extends PageQuery {
  tenantId: string | number;

  contactUserName: string;

  contactPhone: string;

  companyName: string;
}

/**
 * 企业切换 / 当前企业信息返回：切换后旧 token 立即失效，前端必须换用新 token
 */
export interface TenantSwitchResult {
  accessToken?: string;
  access_token?: string;
  expireIn?: number;
  /** 后端 LoginVo 的 expire_in（@JsonProperty），与 expireIn 同义 */
  expire_in?: number;
  clientId?: string;
  /** 后端 LoginVo 的 client_id（@JsonProperty），与 clientId 同义 */
  client_id?: string;
  accountId?: string | number;
  tenantId?: string;
  companyName?: string;
}

export interface TenantForm {
  id: number | string | undefined;
  tenantId: number | string | undefined;
  username: string;
  password: string;
  contactUserName: string;
  contactPhone: string;
  companyName: string;
  licenseNumber: string;
  domain: string;
  address: string;
  intro: string;
  remark: string;
  packageId: string | number;
  /** 省略=不变，null=明确清空，字符串=新日期。 */
  expireTime?: string | null;
  accountCount: number;
  status: string;
}
