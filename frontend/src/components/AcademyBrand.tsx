import Image from "next/image";
import Link from "next/link";

export function AcademyBrand({ href }: { href: string }) {
  return <Link href={href} className="brand" aria-label="Physics Cube Academy home">
    <Image className="brand-logo" src="/physics-cube-academy-logo.jpg"
      alt="Physics Cube Academy" width={806} height={772} sizes="112px" priority />
  </Link>;
}
