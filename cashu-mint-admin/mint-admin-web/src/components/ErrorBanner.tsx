import { AlertTriangle } from "lucide-react";

interface ErrorBannerProps {
  code?: string;
  message: string;
  onRetry?: () => void;
}

export function ErrorBanner({ code, message, onRetry }: ErrorBannerProps) {
  return (
    <div
      role="alert"
      className="rounded-lg border border-red-900/50 bg-red-950/30 p-4 flex items-start gap-3"
    >
      <AlertTriangle className="h-5 w-5 text-red-400 shrink-0 mt-0.5" />
      <div className="flex-1 min-w-0">
        {code && (
          <p className="text-xs font-mono text-red-400 mb-1">{code}</p>
        )}
        <p className="text-sm text-red-200">{message}</p>
      </div>
      {onRetry && (
        <button
          onClick={onRetry}
          className="shrink-0 text-xs font-medium text-red-400 hover:text-red-300 border border-red-800 rounded px-2 py-1"
        >
          Retry
        </button>
      )}
    </div>
  );
}
