"""Create an unsigned MSIX staging directory, using user-supplied Store identity."""
import argparse
import re
import shutil
from pathlib import Path
from xml.etree import ElementTree as ET

NS = 'http://schemas.microsoft.com/appx/manifest/foundation/windows10'
UAP = 'http://schemas.microsoft.com/appx/manifest/uap/windows10'
RES = 'http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities'
ET.register_namespace('', NS)
ET.register_namespace('uap', UAP)
ET.register_namespace('rescap', RES)


def q(ns, name): return '{' + ns + '}' + name


def validate(name, publisher, display_name, publisher_display, version):
    if not re.fullmatch(r'[A-Za-z0-9.-]{3,50}', name):
        raise ValueError('Le nom de package doit être celui attribué par Partner Center (3 à 50 lettres, chiffres, points ou tirets).')
    if not publisher.startswith('CN=') or len(publisher) > 256 or any(ord(c) < 32 for c in publisher):
        raise ValueError('Le Publisher doit correspondre exactement à Partner Center et commencer par CN=.')
    if not 1 <= len(display_name) <= 256 or not 1 <= len(publisher_display) <= 256:
        raise ValueError('Le nom de l’app et celui de l’éditeur sont obligatoires.')
    if any(ord(c) < 32 for c in display_name + publisher_display):
        raise ValueError('Caractère de contrôle interdit.')
    if not re.fullmatch(r'\d+\.\d+\.\d+\.0', version):
        raise ValueError('La version doit avoir quatre nombres et finir par .0, par exemple 1.0.0.0.')
    if any(int(x) > 65535 for x in version.split('.')):
        raise ValueError('Chaque partie de version doit être au plus 65535.')


def create_manifest(name, publisher, display_name, publisher_display, version):
    validate(name, publisher, display_name, publisher_display, version)
    root = ET.Element(q(NS, 'Package'), {'IgnorableNamespaces': 'uap rescap'})
    ET.SubElement(root, q(NS, 'Identity'), {'Name': name, 'Publisher': publisher, 'Version': version, 'ProcessorArchitecture': 'x64'})
    props = ET.SubElement(root, q(NS, 'Properties'))
    for key, value in [('DisplayName', display_name), ('PublisherDisplayName', publisher_display), ('Logo', r'Assets\StoreLogo.png')]:
        ET.SubElement(props, q(NS, key)).text = value
    resources = ET.SubElement(root, q(NS, 'Resources'))
    ET.SubElement(resources, q(NS, 'Resource'), {'Language': 'fr-FR'})
    deps = ET.SubElement(root, q(NS, 'Dependencies'))
    ET.SubElement(deps, q(NS, 'TargetDeviceFamily'), {'Name': 'Windows.Desktop', 'MinVersion': '10.0.17763.0', 'MaxVersionTested': '10.0.26100.0'})
    capabilities = ET.SubElement(root, q(NS, 'Capabilities'))
    ET.SubElement(capabilities, q(NS, 'Capability'), {'Name': 'internetClient'})
    ET.SubElement(capabilities, q(NS, 'DeviceCapability'), {'Name': 'bluetooth'})
    ET.SubElement(capabilities, q(RES, 'Capability'), {'Name': 'runFullTrust'})
    apps = ET.SubElement(root, q(NS, 'Applications'))
    app = ET.SubElement(apps, q(NS, 'Application'), {'Id': 'Maillage', 'Executable': r'app\Maillage-PC.exe', 'EntryPoint': 'Windows.FullTrustApplication'})
    ET.SubElement(app, q(UAP, 'VisualElements'), {
        'DisplayName': display_name, 'Description': 'Maillage : messagerie expérimentale locale et Internet.',
        'BackgroundColor': 'transparent', 'Square150x150Logo': r'Assets\Square150x150Logo.png',
        'Square44x44Logo': r'Assets\Square44x44Logo.png'})
    return ET.ElementTree(root)


def main():
    p = argparse.ArgumentParser(description='Préparer un paquet MSIX non signé destiné à la validation Microsoft Store.')
    p.add_argument('--source', required=True, type=Path)
    p.add_argument('--stage', required=True, type=Path)
    p.add_argument('--identity-name', required=True)
    p.add_argument('--publisher', required=True)
    p.add_argument('--display-name', required=True)
    p.add_argument('--publisher-display', required=True)
    p.add_argument('--version', required=True)
    a = p.parse_args()
    tree = create_manifest(a.identity_name, a.publisher, a.display_name, a.publisher_display, a.version)
    source = a.source.resolve(); stage = a.stage.resolve()
    if not (source / 'Maillage-PC.exe').is_file():
        raise SystemExit('Le programme Windows construit est introuvable.')
    if stage.exists():
        raise SystemExit('Le dossier de préparation existe déjà; refus de le remplacer.')
    if stage == source or source in stage.parents or stage in source.parents:
        raise SystemExit('Le dossier de préparation doit être distinct de la source.')
    assets = Path(__file__).resolve().parents[1] / 'windows-store' / 'assets'
    for asset in ['Square44x44Logo.png', 'Square150x150Logo.png', 'StoreLogo.png']:
        if not (assets / asset).is_file(): raise SystemExit('Image MSIX manquante : ' + asset)
    stage.mkdir(parents=True)
    shutil.copytree(source, stage / 'app')
    shutil.copytree(assets, stage / 'Assets')
    tree.write(stage / 'AppxManifest.xml', encoding='utf-8', xml_declaration=True)
    print('Préparation MSIX créée. Ce paquet n’est ni signé ni certifié.')


if __name__ == '__main__': main()
