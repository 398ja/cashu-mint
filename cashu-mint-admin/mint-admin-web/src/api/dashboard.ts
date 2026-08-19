import { apiGet } from "./client";

export interface DashboardSummary {
  mintsByState: Record<string, number>;
  activeControls: number;
}

export function fetchDashboardSummary(): Promise<DashboardSummary> {
  return apiGet("/admin/dashboard/summary");
}
