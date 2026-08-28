import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { createMint } from "@/api/lifecycle";
import { ErrorBanner } from "@/components/ErrorBanner";
import type { ApiRequestError } from "@/api/client";
import { ArrowLeft } from "lucide-react";

export function CreateMintPage() {
  const navigate = useNavigate();

  const [mintId, setMintId] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [description, setDescription] = useState("");
  const [tags, setTags] = useState("");
  const [unit, setUnit] = useState("sat");
  const [denominations, setDenominations] = useState("1,2,4,8,16,32,64,128");
  const [configuration, setConfiguration] = useState("{}");

  const mutation = useMutation({
    mutationFn: () => {
      const baseConfig = JSON.parse(configuration) as Record<string, unknown>;
      const mergedConfig: Record<string, unknown> = {
        ...baseConfig,
        "cashu.unit": unit,
        "cashu.denominations": denominations,
      };
      return createMint({
        mintId,
        metadata: {
          displayName,
          description,
          tags: tags
            .split(",")
            .map((t) => t.trim())
            .filter(Boolean),
        },
        configuration: mergedConfig,
      });
    },
    onSuccess: () => {
      navigate(`/mints/${mintId}`);
    },
  });

  const valid =
    mintId.trim().length > 0 &&
    displayName.trim().length > 0 &&
    isValidJson(configuration);

  return (
    <div className="space-y-6 max-w-3xl">
      <Link
        to="/mints"
        className="inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"
      >
        <ArrowLeft className="h-4 w-4" /> Back to Mints
      </Link>

      <h1 className="text-xl font-semibold text-zinc-100">Create Mint</h1>

      {mutation.error && (
        <ErrorBanner
          code={(mutation.error as ApiRequestError).code}
          message={(mutation.error as ApiRequestError).message}
        />
      )}

      <div className="space-y-4">
        {/* Mint ID */}
        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-1">
            Mint ID <span className="text-red-400">*</span>
          </label>
          <div className="flex gap-2">
            <input
              type="text"
              value={mintId}
              onChange={(e) => setMintId(e.target.value)}
              placeholder="UUID"
              className="flex-1 rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none font-mono"
            />
            <button
              type="button"
              onClick={() => setMintId(crypto.randomUUID())}
              className="px-3 py-2 text-sm font-medium rounded border border-zinc-700 text-zinc-300 hover:bg-zinc-800"
            >
              Generate
            </button>
          </div>
        </div>

        {/* Display Name */}
        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-1">
            Display Name <span className="text-red-400">*</span>
          </label>
          <input
            type="text"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="My Cashu Mint"
            className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
          />
        </div>

        {/* Description */}
        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-1">
            Description
          </label>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={512}
            rows={3}
            placeholder="Optional description"
            className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none resize-none"
          />
          <span className="text-xs text-zinc-500">{description.length}/512</span>
        </div>

        {/* Tags */}
        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-1">
            Tags
          </label>
          <input
            type="text"
            value={tags}
            onChange={(e) => setTags(e.target.value)}
            placeholder="tag1, tag2, tag3"
            className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
          />
          <span className="text-xs text-zinc-500">Comma-separated</span>
        </div>

        {/* Unit */}
        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-1">
            Unit
          </label>
          <select
            value={unit}
            onChange={(e) => setUnit(e.target.value)}
            className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 focus:border-zinc-600 focus:outline-none"
          >
            <option value="sat">sat</option>
            <option value="usd">usd</option>
            <option value="eur">eur</option>
          </select>
          <span className="text-xs text-zinc-500">Ecash unit for this mint</span>
        </div>

        {/* Denominations */}
        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-1">
            Denominations
          </label>
          <input
            type="text"
            value={denominations}
            onChange={(e) => setDenominations(e.target.value)}
            placeholder="1,2,4,8,16,32,64,128"
            className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none font-mono"
          />
          <span className="text-xs text-zinc-500">Comma-separated integer denominations</span>
        </div>

        {/* Configuration */}
        <div>
          <label className="block text-sm font-medium text-zinc-400 mb-1">
            Configuration
          </label>
          <textarea
            value={configuration}
            onChange={(e) => setConfiguration(e.target.value)}
            rows={4}
            className={`w-full rounded border bg-zinc-800 px-3 py-2 text-sm text-zinc-100 font-mono placeholder:text-zinc-500 focus:outline-none resize-none ${
              isValidJson(configuration)
                ? "border-zinc-700 focus:border-zinc-600"
                : "border-red-700 focus:border-red-600"
            }`}
          />
          {!isValidJson(configuration) && (
            <span className="text-xs text-red-400">Invalid JSON</span>
          )}
        </div>

        <button
          type="button"
          onClick={() => mutation.mutate()}
          disabled={!valid || mutation.isPending}
          className="px-4 py-2 text-sm font-medium rounded bg-zinc-100 text-zinc-900 hover:bg-zinc-200 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {mutation.isPending ? "Creating..." : "Create Mint"}
        </button>
      </div>
    </div>
  );
}

function isValidJson(str: string): boolean {
  try {
    JSON.parse(str);
    return true;
  } catch {
    return false;
  }
}
