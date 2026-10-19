import type { ComponentType, SVGProps } from "react";
import {
  AiIcon,
  ApplicationsIcon,
  HomeIcon,
  JobsIcon,
  ProfileIcon,
} from "../components/icons";

/**
 * The five top-level destinations of the bottom navigation bar. Mirrors the Android
 * `Destination` enum: `label` is the always-visible French tab label, `contentDescription`
 * is the stable English accessibility / test identifier.
 */
export interface Destination {
  route: string;
  label: string;
  contentDescription: string;
  Icon: ComponentType<SVGProps<SVGSVGElement>>;
}

export const DESTINATIONS: Destination[] = [
  { route: "home", label: "Accueil", contentDescription: "Home", Icon: HomeIcon },
  { route: "jobs", label: "Emplois", contentDescription: "Jobs", Icon: JobsIcon },
  {
    route: "applications",
    label: "Candidatures",
    contentDescription: "Applications",
    Icon: ApplicationsIcon,
  },
  { route: "ai", label: "IA", contentDescription: "AI", Icon: AiIcon },
  { route: "profile", label: "Profil", contentDescription: "Profile", Icon: ProfileIcon },
];

export function destinationFromRoute(route: string | null | undefined): Destination {
  return DESTINATIONS.find((d) => d.route === route) ?? DESTINATIONS[0];
}
