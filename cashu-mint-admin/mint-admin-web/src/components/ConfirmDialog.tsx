import { useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";

interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel?: string;
  destructive?: boolean;
  requireReason?: boolean;
  loading?: boolean;
  onConfirm: (reason: string) => void;
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = "Confirm",
  destructive = false,
  requireReason = false,
  loading = false,
  onConfirm,
}: ConfirmDialogProps) {
  const [reason, setReason] = useState("");

  const canConfirm = !requireReason || reason.trim().length > 0;

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/60 data-[state=open]:animate-in data-[state=open]:fade-in" />
        <Dialog.Content className="fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-full max-w-md rounded-lg border border-zinc-800 bg-zinc-900 p-6 shadow-xl focus:outline-none">
          <div className="flex items-start justify-between mb-4">
            <Dialog.Title className="text-lg font-semibold text-zinc-100">
              {title}
            </Dialog.Title>
            <Dialog.Close asChild>
              <button
                className="text-zinc-500 hover:text-zinc-300"
                aria-label="Close"
              >
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>

          <Dialog.Description className="text-sm text-zinc-400 mb-4">
            {description}
          </Dialog.Description>

          {requireReason && (
            <div className="mb-4">
              <label
                htmlFor="confirm-reason"
                className="block text-sm font-medium text-zinc-300 mb-1"
              >
                Reason <span className="text-red-400">*</span>
              </label>
              <textarea
                id="confirm-reason"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none focus:ring-1 focus:ring-zinc-600"
                rows={3}
                placeholder="Provide a reason for this action..."
              />
            </div>
          )}

          <div className="flex justify-end gap-3">
            <Dialog.Close asChild>
              <button className="px-4 py-2 text-sm font-medium text-zinc-300 rounded border border-zinc-700 hover:bg-zinc-800">
                Cancel
              </button>
            </Dialog.Close>
            <button
              onClick={() => {
                onConfirm(reason);
                setReason("");
              }}
              disabled={!canConfirm || loading}
              className={`px-4 py-2 text-sm font-medium rounded disabled:opacity-50 disabled:cursor-not-allowed ${
                destructive
                  ? "bg-red-600 text-white hover:bg-red-700"
                  : "bg-zinc-100 text-zinc-900 hover:bg-zinc-200"
              }`}
            >
              {loading ? "Processing..." : confirmLabel}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
