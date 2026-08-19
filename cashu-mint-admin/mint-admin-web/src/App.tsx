import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
} from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider } from "@/auth/AuthProvider";
import { useAuth } from "@/auth/useAuth";
import { RequireRole } from "@/auth/RequireRole";
import { Layout } from "@/components/Layout";
import { LoginPage } from "@/features/login/LoginPage";
import { DashboardPage } from "@/features/dashboard/DashboardPage";
import { MintListPage } from "@/features/mints/MintListPage";
import { MintDetailPage } from "@/features/mints/MintDetailPage";
import { CreateMintPage } from "@/features/mints/CreateMintPage";
import { UserListPage } from "@/features/users/UserListPage";
import { UserDetailPage } from "@/features/users/UserDetailPage";
import { OperationsPage } from "@/features/operations/OperationsPage";
import { AuditTimelinePage } from "@/features/audit/AuditTimelinePage";
import type { ReactNode } from "react";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

function RequireAuth({ children }: { children: ReactNode }) {
  const { authenticated, loading } = useAuth();
  if (loading) return null;
  if (!authenticated) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              element={
                <RequireAuth>
                  <Layout />
                </RequireAuth>
              }
            >
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route
                path="/mints"
                element={
                  <RequireRole role="MINT_ADMIN">
                    <MintListPage />
                  </RequireRole>
                }
              />
              <Route
                path="/mints/create"
                element={
                  <RequireRole role="MINT_ADMIN">
                    <CreateMintPage />
                  </RequireRole>
                }
              />
              <Route
                path="/mints/:mintId"
                element={
                  <RequireRole role="MINT_ADMIN">
                    <MintDetailPage />
                  </RequireRole>
                }
              />
              <Route
                path="/mints/:mintId/operations"
                element={
                  <RequireRole role="OPS_ADMIN">
                    <OperationsPage />
                  </RequireRole>
                }
              />
              <Route
                path="/users"
                element={
                  <RequireRole role="USER_ADMIN">
                    <UserListPage />
                  </RequireRole>
                }
              />
              <Route
                path="/users/:userId"
                element={
                  <RequireRole role="USER_ADMIN">
                    <UserDetailPage />
                  </RequireRole>
                }
              />
              <Route path="/audit" element={<AuditTimelinePage />} />
              <Route
                path="/"
                element={<Navigate to="/dashboard" replace />}
              />
            </Route>
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
