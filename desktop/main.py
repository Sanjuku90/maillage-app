"""Maillage Desktop, experimental BLE and Nostr channel client."""
import queue
import threading
import tkinter as tk
from tkinter import messagebox, ttk
from collections import OrderedDict
from protocol import ChannelCodec
from nostr import RelayClient
from ble_transport import BleTransport

BG = '#edf1f7'
TEXT = '#101828'
ACCENT = '#4b3fe0'
MUTED = '#566474'


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title('Maillage pour PC · expérimental')
        self.geometry('820x640')
        self.minsize(590, 480)
        self.configure(bg=BG)
        self.protocol('WM_DELETE_WINDOW', self.close)
        self.events = queue.Queue(maxsize=512)
        self.client = None
        self.ble = None
        self.active = False
        self.codec = None
        self.seen = OrderedDict()
        self.generation = 0
        self._build()
        self.after(100, self._drain)

    def _build(self):
        header = tk.Frame(self, bg=BG, padx=24, pady=18)
        header.pack(fill='x')
        tk.Label(header, text='Maillage', font=('Arial', 24, 'bold'), fg=TEXT, bg=BG).pack(anchor='w')
        tk.Label(header, text='PC  ·  Canal local ou par Internet', font=('Arial', 11), fg=MUTED, bg=BG).pack(anchor='w')

        self.connect_bar = tk.Frame(self, bg=BG, padx=24)
        self.connect_bar.pack(fill='x')
        tk.Label(self.connect_bar, text='Pseudo', bg=BG, fg=TEXT).grid(row=0, column=0, sticky='w')
        tk.Label(self.connect_bar, text='Mot de passe du canal', bg=BG, fg=TEXT).grid(row=0, column=1, sticky='w', padx=12)
        self.name = tk.Entry(self.connect_bar, width=17, font=('Arial', 12))
        self.name.insert(0, 'PC')
        self.name.grid(row=1, column=0, sticky='ew', pady=6)
        self.password = tk.Entry(self.connect_bar, show='•', font=('Arial', 12))
        self.password.grid(row=1, column=1, sticky='ew', padx=12, pady=6)
        self.join = tk.Button(self.connect_bar, text='Rejoindre', command=self.connect,
                              bg=ACCENT, fg='white', padx=16, pady=5, relief='flat')
        self.join.grid(row=1, column=2, sticky='ew')
        self.connect_bar.columnconfigure(1, weight=1)

        self.use_ble = tk.BooleanVar(value=True)
        self.use_nostr = tk.BooleanVar(value=False)
        transports = tk.Frame(self, bg=BG, padx=24)
        transports.pack(fill='x')
        tk.Checkbutton(transports, text='Bluetooth local', variable=self.use_ble,
                       bg=BG, fg=TEXT, activebackground=BG).pack(side='left')
        tk.Checkbutton(transports, text='Relais Nostr (Internet)', variable=self.use_nostr,
                       bg=BG, fg=TEXT, activebackground=BG).pack(side='left', padx=12)
        self.use_tor = tk.BooleanVar(value=False)
        self.tor_row = tk.Frame(self, bg=BG, padx=24)
        self.tor_row.pack(fill='x')
        tk.Checkbutton(self.tor_row, text='Passer par Tor local (SOCKS5)', variable=self.use_tor,
                       bg=BG, fg=TEXT, activebackground=BG).pack(side='left')
        tk.Label(self.tor_row, text='Port', bg=BG, fg=TEXT).pack(side='left', padx=(10, 4))
        self.tor_port = tk.Entry(self.tor_row, width=6)
        self.tor_port.insert(0, '9050')
        self.tor_port.pack(side='left')
        warning = ('Bluetooth : téléphone Android Maillage requis. Relais publics : métadonnées visibles. '
                   'Tor doit être lancé séparément sur le PC. Pas de messages privés sur PC.')
        tk.Label(self, text=warning, wraplength=760, justify='left', fg=MUTED, bg=BG,
                 padx=24, pady=10, font=('Arial', 10)).pack(anchor='w')
        self.status = tk.StringVar(value='Déconnecté')
        tk.Label(self, textvariable=self.status, bg=BG, fg=ACCENT, padx=24, font=('Arial', 10, 'bold')).pack(anchor='w')
        pane = tk.Frame(self, bg=BG, padx=24, pady=12)
        pane.pack(fill='both', expand=True)
        self.log = tk.Text(pane, font=('Arial', 12), bg='white', fg=TEXT, wrap='word', relief='flat', padx=16, pady=16, state='disabled')
        scrollbar = ttk.Scrollbar(pane, command=self.log.yview)
        self.log.configure(yscrollcommand=scrollbar.set)
        self.log.pack(side='left', fill='both', expand=True)
        scrollbar.pack(side='right', fill='y')
        compose = tk.Frame(self, bg=BG, padx=24, pady=16)
        compose.pack(fill='x')
        self.input = tk.Entry(compose, font=('Arial', 12), state='disabled')
        self.input.pack(side='left', fill='x', expand=True, ipady=8)
        self.input.bind('<Return>', lambda event: self.send())
        self.send_button = tk.Button(compose, text='Envoyer', command=self.send, bg=ACCENT, fg='white', padx=18, pady=8, relief='flat', state='disabled')
        self.send_button.pack(side='left', padx=(8, 0))
        self._append('Les identités et l’historique du PC ne sont pas conservés après fermeture.')

    def _append(self, text):
        self.log.configure(state='normal')
        self.log.insert('end', text + '\n\n')
        self.log.see('end')
        self.log.configure(state='disabled')

    def _post(self, kind, data, generation):
        try:
            self.events.put_nowait((kind, data, generation))
        except queue.Full:
            pass

    def connect(self):
        if self.active:
            self.disconnect(); return
        pw = self.password.get()
        if len(pw) < 8:
            messagebox.showwarning('Mot de passe', 'Utilise au moins 8 caractères.')
            return
        nickname = self.name.get()
        enable_ble = self.use_ble.get()
        enable_nostr = self.use_nostr.get()
        if not enable_ble and not enable_nostr:
            messagebox.showwarning('Transport', 'Choisis le Bluetooth, Internet ou les deux.')
            return
        tor_port = None
        if self.use_tor.get() and not enable_nostr:
            messagebox.showwarning('Tor', 'Tor s’utilise avec les relais Nostr. Active les relais ou décoche Tor.')
            return
        if self.use_tor.get():
            try:
                tor_port = int(self.tor_port.get())
                if not 1 <= tor_port <= 65535: raise ValueError()
            except ValueError:
                messagebox.showwarning('Tor', 'Entre un port SOCKS local entre 1 et 65535.')
                return
        self.join.configure(state='disabled')
        self.status.set('Préparation du canal…')
        self.generation += 1
        generation = self.generation
        def work():
            try:
                codec = ChannelCodec(pw, nickname)
                client = RelayClient(codec.channel_tag,
                    lambda frame: self._post('frame', frame, generation),
                    lambda status: self._post('status', status, generation), tor_port=tor_port) if enable_nostr else None
                ble = BleTransport(lambda frame: self._post('frame', frame, generation),
                    lambda status: self._post('status', status, generation)) if enable_ble else None
                self._post('ready', (codec, client, ble), generation)
            except Exception as exc:
                self._post('error', str(exc), generation)
        threading.Thread(target=work, daemon=True).start()

    def disconnect(self):
        self.generation += 1
        client, self.client = self.client, None
        ble, self.ble = self.ble, None
        self.active = False
        self.codec = None
        if client:
            threading.Thread(target=client.stop, daemon=True).start()
        if ble:
            ble.stop()
        self.seen.clear()
        self.status.set('Déconnecté')
        self.join.configure(text='Rejoindre', state='normal')
        self.input.configure(state='disabled')
        self.send_button.configure(state='disabled')
        self.password.delete(0, 'end')

    def send(self):
        if not self.active or not self.codec:
            return
        text = self.input.get().strip()
        try:
            frame = self.codec.encode(text)
        except ValueError as exc:
            messagebox.showwarning('Message', str(exc)); return
        try: relay_count = self.client.send(frame) if self.client else 0
        except Exception: relay_count = 0
        try: ble_count = self.ble.send(frame) if self.ble else 0
        except Exception: ble_count = 0
        if not relay_count and not ble_count:
            self.status.set('Aucun transport prêt ou file pleine : message non envoyé')
            return
        self._remember(__import__('json').loads(frame)['i'])
        routes = []
        if ble_count: routes.append('Bluetooth en file')
        if relay_count > 0: routes.append('relais contactés')
        if relay_count == -1: routes.append('relais en attente')
        self._append('Moi · ' + text + '\n' + ', '.join(routes) + ' · réception non confirmée')
        self.input.delete(0, 'end')

    def _remember(self, msg_id):
        self.seen[msg_id] = True
        while len(self.seen) > 2000:
            self.seen.popitem(last=False)

    def _drain(self):
        for _ in range(100):
            try:
                kind, value, generation = self.events.get_nowait()
            except queue.Empty:
                break
            if generation != self.generation:
                if kind == 'ready':
                    if value[1]: value[1].stop()
                    if value[2]: value[2].stop()
                continue
            if kind == 'ready':
                self.codec, self.client, self.ble = value
                self.active = True
                self.password.delete(0, 'end')
                self.status.set('Recherche des connexions…')
                self.join.configure(text='Quitter', state='normal')
                self.input.configure(state='normal')
                self.send_button.configure(state='normal')
                if self.client: self.client.start()
                if self.ble: self.ble.start()
                self._append('Canal rejoint. Les transports sont expérimentaux et la livraison n’est pas garantie.')
            elif kind == 'error':
                self.status.set('Impossible de démarrer')
                self.join.configure(state='normal')
                messagebox.showerror('Erreur', value)
            elif kind == 'status':
                self.status.set(value)
            elif kind == 'frame' and self.codec:
                msg = self.codec.decode(value)
                if msg and msg['id'] not in self.seen:
                    self._remember(msg['id'])
                    self._append(f"{msg['name']}#{msg['tag']} · {msg['text']}")
        self.after(100, self._drain)

    def close(self):
        self.disconnect()
        self.destroy()


if __name__ == '__main__':
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == '--self-test-file':
        from selftest import run
        raise SystemExit(run(sys.argv[2]))
    App().mainloop()
