import requests
import re
import os
import time

# Configuration
CATEGORIES = [
    "https://forums.synthstrom.com/categories/deluge-sample-packs",
    "https://forums.synthstrom.com/categories/deluge-patches"
]
LOG_FILE = 'download_log.md'

def get_gdrive_id(url):
    match = re.search(r'/d/([^/]+)', url)
    if not match: match = re.search(r'id=([^&]+)', url)
    return match.group(1) if match else None

def download_file(url, filepath, session=None):
    if os.path.exists(filepath): return True
    print(f" -> Downloading to {filepath}...")
    try:
        getter = session.get if session else requests.get
        r = getter(url, stream=True, timeout=60)
        if r.status_code == 200:
            with open(filepath, 'wb') as f:
                for chunk in r.iter_content(chunk_size=8192):
                    f.write(chunk)
            return True
    except Exception as e:
        print(f"    Error: {e}")
    return False

def process_url(url, session):
    print(f"Processing Discussion: {url}")
    try:
        r = session.get(url, timeout=10)
        if r.status_code != 200: return
        
        content = r.text
        title_match = re.search(r'<title>(.*?)</title>', content)
        title = title_match.group(1).split(' — ')[0] if title_match else url.split('/')[-1]
        dir_name = re.sub(r'[^\w\s-]', '', title).strip().replace(' ', '_')
        
        links = re.findall(r'href="(.*?)"', content)
        found_files = []
        
        for link in set(links):
            # 1. Handle Direct Forum Uploads (.xml, .zip, etc)
            if any(x in link.lower() for x in ['.zip', '.rar', '.7z', '.xml']):
                if 'uploads/editor' in link or link.endswith(('.zip', '.rar', '.7z', '.xml')):
                    if not link.startswith('http'): link = 'https://forums.synthstrom.com' + link
                    if not os.path.exists(dir_name): os.makedirs(dir_name)
                    filename = link.split('/')[-1]
                    if download_file(link, os.path.join(dir_name, filename), session):
                        found_files.append(filename)

            # 2. Handle Dropbox Public Links
            elif 'dropbox.com/s/' in link:
                if not os.path.exists(dir_name): os.makedirs(dir_name)
                direct_link = link.replace('?dl=0', '').replace('?dl=1', '') + '?dl=1'
                filename = direct_link.split('/')[-1].split('?')[0]
                if download_file(direct_link, os.path.join(dir_name, filename), session):
                    found_files.append(filename)

            # 3. Handle Google Drive Public FILE Links
            elif 'drive.google.com/file/d/' in link or 'drive.google.com/open?id=' in link:
                file_id = get_gdrive_id(link)
                if file_id:
                    if not os.path.exists(dir_name): os.makedirs(dir_name)
                    d_url = f'https://drive.google.com/uc?export=download&id={file_id}'
                    # GDrive Large File Auth
                    res = session.get(d_url, stream=True)
                    token = next((v for k, v in res.cookies.items() if k.startswith('download_warning')), None)
                    if token: res = session.get(d_url + f'&confirm={token}', stream=True)
                    
                    filename = f"gdrive_{file_id}.zip"
                    cd = res.headers.get('Content-Disposition')
                    if cd:
                        fname = re.findall('filename="(.+)"', cd)
                        if fname: filename = fname[0]
                    
                    if download_file(d_url, os.path.join(dir_name, filename), session):
                        found_files.append(filename)

        # Log results
        with open(LOG_FILE, 'a') as f:
            status = "Downloaded" if found_files else "No automated files"
            f.write(f"| {title} | {url} | {status} | {', '.join(found_files)} |\n")
            
    except Exception as e:
        print(f"Error processing {url}: {e}")

def main():
    session = requests.Session()
    session.headers.update({'User-Agent': 'Mozilla/5.0'})
    
    # 1. Get all discussion links
    all_discussions = []
    for cat in CATEGORIES:
        print(f"Crawling category: {cat}")
        for p in range(1, 5): # Check first 4 pages
            try:
                r = session.get(f"{cat}/p{p}")
                links = re.findall(r'href="(https://forums\.synthstrom\.com/discussion/[^"]+)"', r.text)
                all_discussions.extend([l for l in links if not l.endswith('/p1') and not re.search(r'/p\d+$', l)])
            except:
                continue
    
    # 2. Process unique discussions
    for url in sorted(set(all_discussions)):
        process_url(url, session)
        time.sleep(1)

if __name__ == "__main__":
    if not os.path.exists(LOG_FILE):
        with open(LOG_FILE, 'w') as f:
            f.write("# Deluge Download Log\n\n| Title | URL | Status | Files |\n|---|---|---|---|\n")
    main()
