import { generateCorrelationId } from "@/lib/correlation";

export interface ApiError {
  status: number;
  code: string;
  message: string;
}

export class ApiRequestError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public override readonly message: string,
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}

function getAuthHeaders(): Record<string, string> {
  const token = sessionStorage.getItem("admin_token");
  const headers: Record<string, string> = {};
  if (token) headers["X-Admin-Token"] = token;
  return headers;
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (response.status === 401) {
    sessionStorage.removeItem("admin_token");
    window.location.href = "/login?expired=true";
    throw new ApiRequestError(401, "unauthorized", "Session expired");
  }

  if (!response.ok) {
    let code = "unknown_error";
    let message = `Request failed with status ${response.status}`;
    try {
      const body = (await response.json()) as Record<string, unknown>;
      if (typeof body.code === "string") code = body.code;
      if (typeof body.message === "string") message = body.message;
    } catch {
      // response body isn't JSON
    }
    throw new ApiRequestError(response.status, code, message);
  }

  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

async function fetchWithRetry(
  url: string,
  init: RequestInit,
  retries: number,
): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    try {
      const response = await fetch(url, init);
      if (response.status === 503 && attempt < retries) {
        await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt));
        continue;
      }
      return response;
    } catch (err) {
      if (attempt >= retries) throw err;
      await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt));
    }
  }
}

export async function apiGet<T>(
  path: string,
  params?: Record<string, string | number | boolean | null | undefined>,
): Promise<T> {
  const url = new URL(path, window.location.origin);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v != null && v !== "") url.searchParams.set(k, String(v));
    }
  }
  const response = await fetchWithRetry(
    url.toString(),
    {
      method: "GET",
      headers: {
        ...getAuthHeaders(),
        "X-Correlation-Id": generateCorrelationId(),
      },
    },
    2,
  );
  return handleResponse<T>(response);
}

export async function apiPost<T>(
  path: string,
  body?: unknown,
): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: {
      ...getAuthHeaders(),
      "Content-Type": "application/json",
      "X-Correlation-Id": generateCorrelationId(),
    },
    body: body != null ? JSON.stringify(body) : undefined,
  });
  return handleResponse<T>(response);
}

export async function apiPut<T>(
  path: string,
  body?: unknown,
): Promise<T> {
  const response = await fetch(path, {
    method: "PUT",
    headers: {
      ...getAuthHeaders(),
      "Content-Type": "application/json",
      "X-Correlation-Id": generateCorrelationId(),
    },
    body: body != null ? JSON.stringify(body) : undefined,
  });
  return handleResponse<T>(response);
}
