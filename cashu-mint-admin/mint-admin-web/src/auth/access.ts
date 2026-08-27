import { useAuth } from "./useAuth";

/**
 * What a page or a nav entry is gated on: a role or a permission, never both.
 * The optional `never` is what makes the compiler say so.
 */
export type Access =
  | { role: string; permission?: never }
  | { permission: string; role?: never };

/**
 * The one gate, shared by the route guard and the nav so a page can never be
 * offered by one and refused by the other. Ungated means granted.
 */
export function useGranted(): (access?: Access) => boolean {
  const { hasRole, hasPermission } = useAuth();
  return (access) =>
    !access ||
    (access.permission !== undefined
      ? hasPermission(access.permission)
      : hasRole(access.role));
}
